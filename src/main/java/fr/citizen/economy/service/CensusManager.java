package fr.citizen.economy.service;

import fr.citizen.economy.domain.Actor;
import fr.citizen.economy.persistence.Database;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Recensement des citoyens du royaume : prenom, nom, age, profession.
 * Le joueur remplit prenom, nom et age puis valide ; ensuite seule l'administration (ou le Conseil royal) peut modifier.
 * La profession est le secteur de son entreprise (proprietaire, sinon gerant, sinon employe), sauf si un admin en fixe une.
 */
public final class CensusManager {
    public static final int MAX_NAME = 20;
    public static final int MAX_PROFESSION = 24;
    public static final int MAX_AGE = 999;
    public static final String NO_JOB = "Sans emploi";
    private static final Pattern NAME = Pattern.compile("^[\\p{L}][\\p{L} '\\-]*$");
    private static final Pattern PROFESSION = Pattern.compile("^[\\p{L}\\p{N} '&.\\-]+$");

    /** Fiche brute (profession = profession fixee par un admin, vide si automatique). */
    public record Entry(UUID player, String playerName, String firstName, String lastName, int age, String profession,
                        boolean validated, long updatedAt) {
        public boolean complete() { return !firstName.isEmpty() && !lastName.isEmpty() && age > 0; }
    }

    /** Ce qui s'affiche (liste Tab, ecrans) : profession deja resolue. */
    public record Card(String firstName, String lastName, int age, String profession, boolean validated) { }

    /** Ligne de la liste d'administration : tous les joueurs connus, recenses ou non. */
    public record Row(UUID player, String playerName, Entry entry) { }

    private final Database db;

    public CensusManager(Database db) { this.db = db; }

    private static final String SELECT = "SELECT c.player_uuid, p.name, c.first_name, c.last_name, c.age, c.profession, c.validated, c.updated_at "
            + "FROM census c JOIN players p ON p.uuid = c.player_uuid ";

    private static final Sql.RowMapper<Entry> ENTRY = rs -> new Entry(UUID.fromString(rs.getString(1)), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getInt(7) == 1, rs.getLong(8));

    // ------------------------------------------------------------------ lecture

    public CompletableFuture<Optional<Entry>> get(UUID player) {
        return db.read(c -> Sql.one(c, SELECT + "WHERE c.player_uuid = ?", ENTRY, player));
    }

    /** Profession automatique : secteur de l'entreprise ACTIVE du joueur, ou "Sans emploi". */
    public CompletableFuture<String> autoProfession(UUID player) {
        return db.read(c -> Sql.one(c, "SELECT co.sector FROM employees e JOIN companies co ON co.id = e.company_id "
                        + "WHERE e.player_uuid = ? AND e.fired_at IS NULL AND co.status = 'ACTIVE' "
                        + "ORDER BY CASE e.role WHEN 'OWNER' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END, e.hired_at LIMIT 1",
                rs -> rs.getString(1), player).orElse(NO_JOB));
    }

    /** Toutes les fiches resolues, pour la liste Tab (une seule lecture). */
    public CompletableFuture<Map<UUID, Card>> cards() {
        return db.read(c -> {
            Map<UUID, String> jobs = new HashMap<>();
            Sql.query(c, "SELECT e.player_uuid, co.sector FROM employees e JOIN companies co ON co.id = e.company_id "
                    + "WHERE e.fired_at IS NULL AND co.status = 'ACTIVE' "
                    + "ORDER BY e.player_uuid, CASE e.role WHEN 'OWNER' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END, e.hired_at", rs -> {
                jobs.putIfAbsent(UUID.fromString(rs.getString(1)), rs.getString(2));
                return 1;
            });
            Map<UUID, Card> out = new HashMap<>();
            for (Entry e : Sql.query(c, SELECT, ENTRY)) out.put(e.player(), card(e, jobs.get(e.player())));
            return out;
        });
    }

    static Card card(Entry e, String job) {
        String prof = !e.profession().isEmpty() ? e.profession() : (job == null || job.isBlank() ? NO_JOB : job);
        return new Card(e.firstName(), e.lastName(), e.age(), clip(prof, MAX_PROFESSION), e.validated());
    }

    public CompletableFuture<List<Row>> list(Actor admin, int limit) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT p.uuid, p.name, c.player_uuid, p.name, c.first_name, c.last_name, c.age, c.profession, c.validated, c.updated_at "
                            + "FROM players p LEFT JOIN census c ON c.player_uuid = p.uuid "
                            + "ORDER BY c.validated IS NULL, c.validated, c.last_name, c.first_name, p.name LIMIT ?",
                    rs -> {
                        UUID id = UUID.fromString(rs.getString(1));
                        Entry e = rs.getString(3) == null ? null : new Entry(id, rs.getString(4), rs.getString(5), rs.getString(6),
                                rs.getInt(7), rs.getString(8), rs.getInt(9) == 1, rs.getLong(10));
                        return new Row(id, rs.getString(2), e);
                    }, Math.max(1, Math.min(limit, 150)));
        });
    }

    // ------------------------------------------------------------------ joueur

    /** Le joueur remplit sa propre fiche ; refuse une fois validee. */
    public CompletableFuture<Void> fill(Actor actor, String firstName, String lastName, String age) {
        String fn = name(firstName, "Prénom"), ln = name(lastName, "Nom");
        int ag = age(age);
        return db.write(c -> {
            if (actor.isCouncil()) throw new EconomyException("FORBIDDEN", "Le Conseil royal n'a pas de fiche de recensement.");
            long now = System.currentTimeMillis();
            Records.touchPlayer(c, actor.id(), actor.name(), now);
            Optional<Entry> cur = Sql.one(c, SELECT + "WHERE c.player_uuid = ?", ENTRY, actor.id());
            if (cur.isPresent() && cur.get().validated())
                throw new EconomyException("LOCKED", "Votre recensement est validé : seule l'administration peut le modifier.");
            Sql.update(c, "INSERT INTO census(player_uuid,first_name,last_name,age,updated_at) VALUES (?,?,?,?,?) "
                    + "ON CONFLICT(player_uuid) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name, "
                    + "age = excluded.age, updated_at = excluded.updated_at", actor.id(), fn, ln, ag, now);
            return null;
        });
    }

    public CompletableFuture<Void> validate(Actor actor) {
        return db.write(c -> {
            Entry e = Sql.one(c, SELECT + "WHERE c.player_uuid = ?", ENTRY, actor.id())
                    .orElseThrow(() -> new EconomyException("NOT_FOUND", "Remplissez d'abord votre prénom, votre nom et votre âge."));
            if (e.validated()) throw new EconomyException("LOCKED", "Votre recensement est déjà validé.");
            if (!e.complete()) throw new EconomyException("INCOMPLETE", "Recensement incomplet : prénom, nom et âge sont obligatoires.");
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE census SET validated = 1, validated_at = ?, updated_at = ? WHERE player_uuid = ?", now, now, actor.id());
            Records.audit(c, actor.id(), "CENSUS_VALIDATE", "PLAYER", actor.id(), e.firstName() + " " + e.lastName() + ", " + e.age() + " ans", now);
            return null;
        });
    }

    // ------------------------------------------------------------------ administration

    /** Un admin corrige une fiche (meme validee). Profession vide = profession automatique de l'entreprise. */
    public CompletableFuture<Void> adminSet(Actor admin, UUID player, String firstName, String lastName, String age, String profession) {
        String fn = name(firstName, "Prénom"), ln = name(lastName, "Nom");
        int ag = age(age);
        String prof = profession(profession);
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            if (!Sql.exists(c, "SELECT 1 FROM players WHERE uuid = ?", player))
                throw new EconomyException("NOT_FOUND", "Joueur inconnu.");
            long now = System.currentTimeMillis();
            Sql.update(c, "INSERT INTO census(player_uuid,first_name,last_name,age,profession,updated_at) VALUES (?,?,?,?,?,?) "
                    + "ON CONFLICT(player_uuid) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name, "
                    + "age = excluded.age, profession = excluded.profession, updated_at = excluded.updated_at", player, fn, ln, ag, prof, now);
            Records.audit(c, admin.id(), "CENSUS_EDIT", "PLAYER", player, fn + " " + ln + ", " + ag + " ans" + (prof.isEmpty() ? "" : ", " + prof), now);
            Records.notify(c, player, "CENSUS", "L'administration a mis à jour votre recensement : " + fn + " " + ln + ", " + ag + " ans.", now);
            return null;
        });
    }

    public CompletableFuture<Void> adminUnlock(Actor admin, UUID player) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            long now = System.currentTimeMillis();
            if (Sql.update(c, "UPDATE census SET validated = 0, validated_at = NULL, updated_at = ? WHERE player_uuid = ?", now, player) == 0)
                throw new EconomyException("NOT_FOUND", "Ce joueur n'est pas recensé.");
            Records.audit(c, admin.id(), "CENSUS_UNLOCK", "PLAYER", player, "", now);
            Records.notify(c, player, "CENSUS", "Votre recensement a été rouvert : corrigez-le puis validez-le à nouveau (/recensement).", now);
            return null;
        });
    }

    public CompletableFuture<Void> adminValidate(Actor admin, UUID player) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            Entry e = Sql.one(c, SELECT + "WHERE c.player_uuid = ?", ENTRY, player)
                    .orElseThrow(() -> new EconomyException("NOT_FOUND", "Ce joueur n'est pas recensé."));
            if (!e.complete()) throw new EconomyException("INCOMPLETE", "Fiche incomplète : prénom, nom et âge sont obligatoires.");
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE census SET validated = 1, validated_at = ?, updated_at = ? WHERE player_uuid = ?", now, now, player);
            Records.audit(c, admin.id(), "CENSUS_VALIDATE", "PLAYER", player, "par l'administration", now);
            return null;
        });
    }

    public CompletableFuture<Void> adminDelete(Actor admin, UUID player) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            long now = System.currentTimeMillis();
            if (Sql.update(c, "DELETE FROM census WHERE player_uuid = ?", player) == 0)
                throw new EconomyException("NOT_FOUND", "Ce joueur n'est pas recensé.");
            Records.audit(c, admin.id(), "CENSUS_DELETE", "PLAYER", player, "", now);
            Records.notify(c, player, "CENSUS", "Votre recensement a été effacé par l'administration : refaites-le avec /recensement.", now);
            return null;
        });
    }

    // ------------------------------------------------------------------ validation des saisies

    static String name(String s, String field) {
        String v = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) throw new EconomyException("BAD_TEXT", field + " obligatoire.");
        if (v.length() > MAX_NAME) throw new EconomyException("BAD_TEXT", field + " : " + MAX_NAME + " caractères maximum.");
        if (!NAME.matcher(v).matches()) throw new EconomyException("BAD_TEXT", field + " : lettres, espaces, ' et - uniquement.");
        return v;
    }

    static int age(String s) {
        int v;
        try { v = Integer.parseInt(s == null ? "" : s.trim()); }
        catch (NumberFormatException e) { throw new EconomyException("BAD_AGE", "Âge invalide : indiquez un nombre."); }
        if (v < 1 || v > MAX_AGE) throw new EconomyException("BAD_AGE", "Âge invalide (1 à " + MAX_AGE + ").");
        return v;
    }

    static String profession(String s) {
        String v = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) return "";
        if (v.length() > MAX_PROFESSION) throw new EconomyException("BAD_TEXT", "Profession : " + MAX_PROFESSION + " caractères maximum.");
        if (!PROFESSION.matcher(v).matches()) throw new EconomyException("BAD_TEXT", "Profession : caractères interdits.");
        return v;
    }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max).trim(); }
}
