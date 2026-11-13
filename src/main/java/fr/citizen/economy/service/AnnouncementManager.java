package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.PermissionManager.Authority;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Annonces internes/publiques, moderation, protection anti-spam et limites configurables a chaud. */
public final class AnnouncementManager {
    public record Limits(int maxTitle, int maxBody, int perHour, int cooldownSeconds, int maxActivePerCompany) {
        public static final Limits DEFAULT = new Limits(60, 1000, 5, 30, 50);
    }

    private static final long DAY = 86_400_000L;

    private final Database db;
    private final LongSupplier clock;

    public AnnouncementManager(Database db) { this(db, System::currentTimeMillis); }
    public AnnouncementManager(Database db, LongSupplier clock) { this.db = db; this.clock = clock; }

    private static final String SELECT =
            "SELECT a.id, a.company_id, co.name, a.author_uuid, p.name, a.title, a.body, a.category, a.visibility, a.status, "
          + "a.created_at, a.updated_at, a.expires_at, a.moderated_by FROM announcements a "
          + "JOIN companies co ON co.id = a.company_id JOIN players p ON p.uuid = a.author_uuid ";

    private static final Sql.RowMapper<Announcement> MAPPER = rs -> new Announcement(rs.getLong(1), rs.getLong(2), rs.getString(3),
            UUID.fromString(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7),
            AnnouncementCategory.valueOf(rs.getString(8)), AnnouncementVisibility.valueOf(rs.getString(9)), rs.getString(10),
            rs.getLong(11), rs.getLong(12), rs.getObject(13) == null ? null : rs.getLong(13), rs.getString(14));

    // ------------------------------------------------------------------ publication

    public CompletableFuture<Long> publish(Actor actor, long companyId, String title, String body, AnnouncementCategory category,
                                           AnnouncementVisibility visibility, Long expiresAt) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, Permission.PUBLISH_ANNOUNCEMENTS, true);
            if (a.member() == null) throw new EconomyException("FORBIDDEN", "Un administrateur ne publie pas au nom d'une entreprise.");
            if (a.company().announcementsSuspended())
                throw new EconomyException("PUBLISH_SUSPENDED", "Les publications de cette entreprise sont suspendues.");
            Limits L = limits(c);
            long now = clock.getAsLong();
            String t = Validation.text(title, 3, L.maxTitle(), "Titre");
            String b = Validation.text(body, 3, L.maxBody(), "Description");
            if (category == null || visibility == null) throw new EconomyException("BAD_TEXT", "Catégorie et visibilité requises.");
            checkExpiry(expiresAt, now);

            // --- anti-spam (les annonces supprimees comptent : supprimer ne contourne pas la limite)
            if (Sql.scalar(c, "SELECT COUNT(*) FROM announcements WHERE author_uuid = ? AND created_at >= ?", actor.id(), now - 3_600_000L) >= L.perHour())
                throw new EconomyException("RATE_LIMIT", "Trop d'annonces : maximum " + L.perHour() + " par heure.");
            if (Sql.scalar(c, "SELECT COUNT(*) FROM announcements WHERE author_uuid = ? AND created_at >= ?", actor.id(), now - L.cooldownSeconds() * 1000L) > 0)
                throw new EconomyException("COOLDOWN", "Patientez " + L.cooldownSeconds() + " s entre deux annonces.");
            if (Sql.exists(c, "SELECT 1 FROM announcements WHERE company_id = ? AND title = ? COLLATE NOCASE AND body = ? COLLATE NOCASE AND created_at >= ?",
                    companyId, t, b, now - DAY))
                throw new EconomyException("DUPLICATE", "Cette annonce existe déjà.");
            if (Sql.scalar(c, "SELECT COUNT(*) FROM announcements WHERE company_id = ? AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > ?)", companyId, now) >= L.maxActivePerCompany())
                throw new EconomyException("COMPANY_LIMIT", "Trop d'annonces actives pour cette entreprise (" + L.maxActivePerCompany() + ").");

            long id = Sql.insert(c, "INSERT INTO announcements(company_id,author_uuid,title,body,category,visibility,status,created_at,updated_at,expires_at) "
                    + "VALUES (?,?,?,?,?,?, 'ACTIVE', ?,?,?)", companyId, actor.id(), t, b, category, visibility, now, now, expiresAt);
            Records.audit(c, actor.id(), "ANNOUNCEMENT_PUBLISHED", "announcement", id, visibility + " " + t, now);
            if (visibility == AnnouncementVisibility.INTERNAL) {
                for (Member m : Records.activeMembers(c, companyId))
                    if (!m.player().equals(actor.id()))
                        Records.notify(c, m.player(), "ANNOUNCEMENT", "Annonce interne chez « " + a.company().name() + " » : " + t, now);
            }
            return id;
        });
    }

    public CompletableFuture<Void> edit(Actor actor, long announcementId, String title, String body, AnnouncementCategory category,
                                        AnnouncementVisibility visibility, Long expiresAt) {
        return db.write(c -> {
            Announcement an = get(c, announcementId);
            if (!an.status().equals("ACTIVE")) throw new EconomyException("BAD_STATE", "Cette annonce n'est plus active.");
            if (actor.admin()) throw new EconomyException("FORBIDDEN", "L'administration supprime ou suspend, elle ne modifie pas le contenu.");
            Authority a = PermissionManager.authorize(c, actor, an.companyId(), null, true);
            boolean own = an.author().equals(actor.id()) && PermissionManager.has(a.member(), Permission.PUBLISH_ANNOUNCEMENTS);
            if (!own && !PermissionManager.has(a.member(), Permission.MANAGE_ANNOUNCEMENTS))
                throw new EconomyException("FORBIDDEN", "Vous ne pouvez modifier que vos propres annonces.");
            if (a.company().announcementsSuspended()) throw new EconomyException("PUBLISH_SUSPENDED", "Les publications de cette entreprise sont suspendues.");
            Limits L = limits(c);
            long now = clock.getAsLong();
            String t = Validation.text(title, 3, L.maxTitle(), "Titre");
            String b = Validation.text(body, 3, L.maxBody(), "Description");
            if (category == null || visibility == null) throw new EconomyException("BAD_TEXT", "Catégorie et visibilité requises.");
            checkExpiry(expiresAt, now);
            Sql.update(c, "UPDATE announcements SET title=?, body=?, category=?, visibility=?, expires_at=?, updated_at=? WHERE id=?",
                    t, b, category, visibility, expiresAt, now, announcementId);
            Records.audit(c, actor.id(), "ANNOUNCEMENT_EDITED", "announcement", announcementId, t, now);
            return null;
        });
    }

    /** Suppression par l'auteur ou un detenteur de MANAGE_ANNOUNCEMENTS (suppression logique, conservee dans l'historique). */
    public CompletableFuture<Void> delete(Actor actor, long announcementId) {
        return db.write(c -> {
            Announcement an = get(c, announcementId);
            if (!an.status().equals("ACTIVE")) return null;
            Authority a = PermissionManager.authorize(c, actor, an.companyId(), null, false);
            if (!a.admin()) {
                boolean own = an.author().equals(actor.id());
                if (!own && !PermissionManager.has(a.member(), Permission.MANAGE_ANNOUNCEMENTS))
                    throw new EconomyException("FORBIDDEN", "Vous ne pouvez supprimer que vos propres annonces.");
            }
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE announcements SET status='REMOVED', updated_at=?, moderated_by=? WHERE id=?",
                    now, a.admin() ? actor.id().toString() : null, announcementId);
            Records.audit(c, actor.id(), "ANNOUNCEMENT_REMOVED", "announcement", announcementId, an.title(), now);
            return null;
        });
    }

    // ------------------------------------------------------------------ moderation (admin)

    public CompletableFuture<Void> adminRemove(Actor admin, long announcementId, String reason) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            Announcement an = get(c, announcementId);
            String why = Validation.text(reason, 3, 300, "Motif");
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE announcements SET status='REMOVED', updated_at=?, moderated_by=? WHERE id=?", now, admin.id().toString(), announcementId);
            Records.audit(c, admin.id(), "ANNOUNCEMENT_MODERATED", "announcement", announcementId, why, now);
            Records.notify(c, an.author(), "ANNOUNCEMENT_REMOVED", "Votre annonce « " + an.title() + " » a été retirée par l'administration. Motif : " + why, now);
            return null;
        });
    }

    public CompletableFuture<Void> setPublicationsSuspended(Actor admin, long companyId, boolean suspended, String reason) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            Company co = Records.company(c, companyId);
            String why = Validation.text(reason, 3, 300, "Motif");
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE companies SET announcements_suspended=?, updated_at=? WHERE id=?", suspended, now, companyId);
            Records.audit(c, admin.id(), suspended ? "ANNOUNCEMENTS_SUSPENDED" : "ANNOUNCEMENTS_RESTORED", "company", companyId, why, now);
            Records.notify(c, co.owner(), "ANNOUNCEMENTS", suspended
                    ? "Les publications de « " + co.name() + " » sont suspendues. Motif : " + why
                    : "Les publications de « " + co.name() + " » sont de nouveau autorisées.", now);
            return null;
        });
    }

    public CompletableFuture<Void> setLimits(Actor admin, Limits l) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            if (l.maxTitle() < 10 || l.maxTitle() > 100 || l.maxBody() < 50 || l.maxBody() > 2000 || l.perHour() < 1 || l.perHour() > 60
                    || l.cooldownSeconds() < 0 || l.cooldownSeconds() > 600 || l.maxActivePerCompany() < 1 || l.maxActivePerCompany() > 500)
                throw new EconomyException("BAD_LIMITS", "Limites hors plage autorisée.");
            long now = clock.getAsLong();
            String by = admin.id().toString();
            Settings.set(c, "announce.max_title", Integer.toString(l.maxTitle()), by, now);
            Settings.set(c, "announce.max_body", Integer.toString(l.maxBody()), by, now);
            Settings.set(c, "announce.per_hour", Integer.toString(l.perHour()), by, now);
            Settings.set(c, "announce.cooldown_s", Integer.toString(l.cooldownSeconds()), by, now);
            Settings.set(c, "announce.max_active", Integer.toString(l.maxActivePerCompany()), by, now);
            Records.audit(c, admin.id(), "ANNOUNCEMENT_LIMITS", "settings", "announce", l.toString(), now);
            return null;
        });
    }

    // ------------------------------------------------------------------ lecture

    /** Panneau general : annonces publiques actives, non expirees, d'entreprises actives. */
    public CompletableFuture<List<Announcement>> publicBoard(AnnouncementCategory categoryOrNull, int limit, int offset) {
        return db.read(c -> Sql.query(c, SELECT + "WHERE a.visibility = 'PUBLIC' AND a.status = 'ACTIVE' AND co.status = 'ACTIVE' "
                + "AND (a.expires_at IS NULL OR a.expires_at > ?) AND (? IS NULL OR a.category = ?) ORDER BY a.created_at DESC LIMIT ? OFFSET ?",
                MAPPER, clock.getAsLong(), categoryOrNull, categoryOrNull, clamp(limit), Math.max(0, offset)));
    }

    /** Panneau de l'entreprise : internes + publiques, reserve aux membres (et a l'administration). */
    public CompletableFuture<List<Announcement>> companyBoard(Actor actor, long companyId, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.authorize(c, actor, companyId, null, false);
            return Sql.query(c, SELECT + "WHERE a.company_id = ? AND a.status = 'ACTIVE' AND (a.expires_at IS NULL OR a.expires_at > ?) "
                    + "ORDER BY a.created_at DESC LIMIT ? OFFSET ?", MAPPER, companyId, clock.getAsLong(), clamp(limit), Math.max(0, offset));
        });
    }

    public CompletableFuture<List<Announcement>> adminList(Actor admin, String statusOrNull, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, SELECT + "WHERE (? IS NULL OR a.status = ?) ORDER BY a.id DESC LIMIT ? OFFSET ?",
                    MAPPER, statusOrNull, statusOrNull, clamp(limit), Math.max(0, offset));
        });
    }

    public CompletableFuture<Limits> limits() { return db.read(AnnouncementManager::limits); }

    // ------------------------------------------------------------------ interne

    private static int clamp(int limit) { return Math.max(1, Math.min(limit, 100)); }

    private static void checkExpiry(Long expiresAt, long now) {
        if (expiresAt != null && (expiresAt <= now || expiresAt > now + 365 * DAY))
            throw new EconomyException("BAD_EXPIRY", "Date d'expiration invalide (dans le futur, 1 an maximum).");
    }

    private static Announcement get(Connection c, long id) throws SQLException {
        return Sql.one(c, SELECT + "WHERE a.id = ?", MAPPER, id).orElseThrow(() -> new EconomyException("NOT_FOUND", "Annonce introuvable."));
    }

    private static Limits limits(Connection c) throws SQLException {
        Limits d = Limits.DEFAULT;
        return new Limits(intSetting(c, "announce.max_title", d.maxTitle()), intSetting(c, "announce.max_body", d.maxBody()),
                intSetting(c, "announce.per_hour", d.perHour()), intSetting(c, "announce.cooldown_s", d.cooldownSeconds()),
                intSetting(c, "announce.max_active", d.maxActivePerCompany()));
    }

    private static int intSetting(Connection c, String key, int def) throws SQLException {
        return Settings.get(c, key).map(Integer::parseInt).orElse(def);
    }
}
