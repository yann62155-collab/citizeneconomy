package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Coffres d'entreprise : enregistrement en base (la serrure vit dans {@link ChestLocks}). */
public final class ChestManager {
    public record Chest(long id, long companyId, String dimension, int x, int y, int z) { }

    /** Photo des membres des entreprises actives, pour verifier rapidement qui peut ouvrir quoi. */
    public record Snapshot(Map<UUID, Set<Long>> members, Map<UUID, Set<Long>> staff, Map<Long, String> names) { }

    private final Database db;
    private final LongSupplier clock;

    public ChestManager(Database db) { this(db, System::currentTimeMillis); }
    public ChestManager(Database db, LongSupplier clock) { this.db = db; this.clock = clock; }

    /** Enregistre un coffre pour l'entreprise. Patron ou gerant de l'entreprise (ou admin). */
    public CompletableFuture<Long> register(Actor actor, long companyId, String dim, int x, int y, int z) {
        return db.write(c -> {
            Company co = Records.company(c, companyId);
            if (co.status() != CompanyStatus.ACTIVE) throw new EconomyException("COMPANY_NOT_ACTIVE", "L'entreprise n'est pas active.");
            Optional<Member> m = Records.activeMember(c, companyId, actor.id());
            boolean staff = m.isPresent() && m.get().role() != CompanyRole.EMPLOYEE;
            if (!staff && !actor.admin()) throw new EconomyException("FORBIDDEN", "Seul le patron ou un gérant peut poser un coffre d'entreprise.");
            if (Sql.exists(c, "SELECT 1 FROM company_chests WHERE dimension = ? AND x = ? AND y = ? AND z = ?", dim, x, y, z))
                throw new EconomyException("ALREADY", "Ce coffre est déjà un coffre d'entreprise.");
            long now = clock.getAsLong();
            long id = Sql.insert(c, "INSERT INTO company_chests(company_id,dimension,x,y,z,placed_by,created_at) VALUES (?,?,?,?,?,?,?)",
                    companyId, dim, x, y, z, actor.id(), now);
            Records.audit(c, actor.id(), "CHEST_PLACED", "company", companyId, dim + " " + x + " " + y + " " + z, now);
            return id;
        });
    }

    public CompletableFuture<Boolean> remove(Actor actor, String dim, int x, int y, int z) {
        return db.write(c -> {
            long now = clock.getAsLong();
            Optional<Long> company = Sql.one(c, "SELECT company_id FROM company_chests WHERE dimension = ? AND x = ? AND y = ? AND z = ?",
                    rs -> rs.getLong(1), dim, x, y, z);
            if (company.isEmpty()) return false;
            Sql.update(c, "DELETE FROM company_chests WHERE dimension = ? AND x = ? AND y = ? AND z = ?", dim, x, y, z);
            Records.audit(c, actor.id(), "CHEST_REMOVED", "company", company.get(), dim + " " + x + " " + y + " " + z, now);
            return true;
        });
    }

    public CompletableFuture<List<Chest>> all() {
        return db.read(c -> Sql.query(c, "SELECT id, company_id, dimension, x, y, z FROM company_chests",
                rs -> new Chest(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6))));
    }

    public CompletableFuture<Snapshot> snapshot() {
        return db.read(c -> {
            Map<UUID, Set<Long>> members = new HashMap<>(), staff = new HashMap<>();
            Sql.query(c, "SELECT e.player_uuid, e.company_id, e.role FROM employees e JOIN companies co ON co.id = e.company_id "
                    + "WHERE e.fired_at IS NULL AND co.status IN ('ACTIVE','SUSPENDED')", rs -> {
                UUID p = UUID.fromString(rs.getString(1));
                members.computeIfAbsent(p, k -> new HashSet<>()).add(rs.getLong(2));
                if (!rs.getString(3).equals("EMPLOYEE")) staff.computeIfAbsent(p, k -> new HashSet<>()).add(rs.getLong(2));
                return 0;
            });
            Map<Long, String> names = new HashMap<>();
            Sql.query(c, "SELECT id, name FROM companies WHERE status IN ('ACTIVE','SUSPENDED')", rs -> { names.put(rs.getLong(1), rs.getString(2)); return 0; });
            return new Snapshot(members, staff, names);
        });
    }

    /** Nom d'une entreprise active (pour nommer l'objet donne par l'admin) ; echoue si elle n'existe pas ou n'est pas active. */
    public CompletableFuture<String> activeCompanyName(long companyId) {
        return db.read(c -> {
            Company co = Records.company(c, companyId);
            if (co.status() != CompanyStatus.ACTIVE) throw new EconomyException("COMPANY_NOT_ACTIVE", "L'entreprise n'est pas active.");
            return co.name();
        });
    }

    public record CompanyRef(long id, String name) { }

    /** Retrouve une entreprise active par son numero ou par son nom (insensible a la casse). */
    public CompletableFuture<CompanyRef> resolveActive(String idOrName) {
        return db.read(c -> {
            String q = idOrName == null ? "" : idOrName.trim();
            Optional<CompanyRef> r = Optional.empty();
            if (q.matches("\\d{1,15}"))
                r = Sql.one(c, "SELECT id, name FROM companies WHERE id = ? AND status = 'ACTIVE'", rs -> new CompanyRef(rs.getLong(1), rs.getString(2)), Long.parseLong(q));
            if (r.isEmpty())
                r = Sql.one(c, "SELECT id, name FROM companies WHERE name = ? COLLATE NOCASE AND status = 'ACTIVE'", rs -> new CompanyRef(rs.getLong(1), rs.getString(2)), q);
            return r.orElseThrow(() -> new EconomyException("NOT_FOUND", "Entreprise active introuvable : « " + q + " »."));
        });
    }
}
