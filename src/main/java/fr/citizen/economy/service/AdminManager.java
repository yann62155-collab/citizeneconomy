package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Fonctions d'administration transverses : tableau de bord, journaux, proprietaires, parametres generaux. */
public final class AdminManager {
    public record Dashboard(Map<String, Long> companiesByStatus, long pendingRequests, long players, long personalTotal,
                            long businessTotal, long treasury, long openInvoices, long activeAnnouncements) { }
    public record AdminMember(Member member, String companyName, String companyStatus) { }
    public record TxRow(long id, String type, long amount, String source, String target, String description, long createdAt) { }
    public record AuditRow(long id, String actor, String action, String target, String details, long createdAt) { }

    private final Database db;

    public AdminManager(Database db) { this.db = db; }

    public CompletableFuture<Dashboard> dashboard(Actor admin) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (CompanyStatus s : CompanyStatus.values()) byStatus.put(s.name(), 0L);
            Sql.query(c, "SELECT status, COUNT(*) FROM companies GROUP BY status", rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); return 1; });
            return new Dashboard(byStatus,
                    Sql.scalar(c, "SELECT COUNT(*) FROM company_requests WHERE status = 'PENDING'"),
                    Sql.scalar(c, "SELECT COUNT(*) FROM players"),
                    Sql.scalar(c, "SELECT COALESCE(SUM(balance),0) FROM personal_accounts"),
                    Sql.scalar(c, "SELECT COALESCE(SUM(balance),0) FROM business_accounts"),
                    Sql.scalar(c, "SELECT balance FROM treasury WHERE id = 1"),
                    Sql.scalar(c, "SELECT COUNT(*) FROM invoices WHERE status IN ('PENDING','OVERDUE')"),
                    Sql.scalar(c, "SELECT COUNT(*) FROM announcements WHERE status = 'ACTIVE'"));
        });
    }

    /** Membres actifs de toutes les entreprises (filtre de role facultatif). */
    public CompletableFuture<List<AdminMember>> members(Actor admin, CompanyRole roleOrNull, int limit) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            String role = roleOrNull == null ? null : roleOrNull.name();
            return Sql.query(c, "SELECT e.id, e.company_id, e.player_uuid, p.name, e.role, e.grade, e.salary, e.permissions, e.hired_at, co.name, co.status "
                    + "FROM employees e JOIN players p ON p.uuid = e.player_uuid JOIN companies co ON co.id = e.company_id "
                    + "WHERE e.fired_at IS NULL AND co.status IN ('ACTIVE','SUSPENDED') AND (? IS NULL OR e.role = ?) ORDER BY co.name, p.name LIMIT ?",
                    rs -> new AdminMember(Records.MEMBER.map(rs), rs.getString(10), rs.getString(11)), role, role, Math.max(1, Math.min(limit, 100)));
        });
    }

    /** Transfere la propriete : l'ancien proprietaire devient simple employe. */
    public CompletableFuture<Void> transferOwnership(Actor admin, long companyId, UUID newOwner, String reason) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            Company co = Records.company(c, companyId);
            if (co.status() == CompanyStatus.CLOSED || co.status() == CompanyStatus.PENDING)
                throw new EconomyException("BAD_STATE", "Entreprise non modifiable.");
            String why = Validation.text(reason, 3, 300, "Motif");
            Member target = Records.activeMember(c, companyId, newOwner)
                    .orElseThrow(() -> new EconomyException("NOT_FOUND", "Le nouveau propriétaire doit déjà être membre de l'entreprise."));
            if (target.role() == CompanyRole.OWNER) throw new EconomyException("BAD_STATE", "Ce joueur est déjà propriétaire.");
            long now = System.currentTimeMillis();
            Member old = Records.activeMember(c, companyId, co.owner()).orElseThrow();
            Sql.update(c, "UPDATE employees SET role='EMPLOYEE', grade='Employé', permissions=? WHERE id=?",
                    PermissionManager.defaultsFor(CompanyRole.EMPLOYEE), old.employeeId());
            Sql.update(c, "UPDATE employees SET role='OWNER', grade='Propriétaire', permissions=? WHERE id=?", PermissionManager.ALL, target.employeeId());
            Sql.update(c, "UPDATE companies SET owner_uuid=?, manager_uuid=CASE WHEN manager_uuid = ? THEN NULL ELSE manager_uuid END, updated_at=? WHERE id=?",
                    newOwner, newOwner.toString(), now, companyId);
            Records.audit(c, admin.id(), "OWNERSHIP_TRANSFERRED", "company", companyId, co.owner() + " -> " + newOwner + " : " + why, now);
            Records.notify(c, newOwner, "OWNER", "Vous êtes maintenant propriétaire de « " + co.name() + " ».", now);
            Records.notify(c, co.owner(), "OWNER", "La propriété de « " + co.name() + " » a été transférée. Motif : " + why, now);
            return null;
        });
    }

    private static final String LABEL = "CASE %1$s_type WHEN 'PERSONAL' THEN COALESCE((SELECT name FROM players WHERE uuid = %1$s_id),'?') "
            + "WHEN 'BUSINESS' THEN COALESCE((SELECT name FROM companies WHERE id = CAST(%1$s_id AS INTEGER)),'?') WHEN 'TREASURY' THEN 'Tresor royal' ELSE 'Especes' END";

    public CompletableFuture<List<TxRow>> transactions(Actor admin, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT id, type, amount, " + LABEL.formatted("source") + ", " + LABEL.formatted("target")
                    + ", description, created_at FROM transactions ORDER BY id DESC LIMIT ? OFFSET ?",
                    rs -> new TxRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)),
                    Math.max(1, Math.min(limit, 100)), Math.max(0, offset));
        });
    }

    public CompletableFuture<List<AuditRow>> audit(Actor admin, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT a.id, COALESCE(p.name, 'systeme'), a.action, COALESCE(a.target_type || ':' || a.target_id, ''), a.details, a.created_at "
                    + "FROM audit_logs a LEFT JOIN players p ON p.uuid = a.actor_uuid ORDER BY a.id DESC LIMIT ? OFFSET ?",
                    rs -> new AuditRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)),
                    Math.max(1, Math.min(limit, 100)), Math.max(0, offset));
        });
    }

    public CompletableFuture<String> timeZone() {
        return db.read(c -> Settings.get(c, Settings.TAX_ZONE).orElse(ZoneId.systemDefault().getId()));
    }

    public CompletableFuture<Void> setTimeZone(Actor admin, String zone) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            String z;
            try { z = ZoneId.of(zone == null ? "" : zone.trim()).getId(); }
            catch (Exception e) { throw new EconomyException("BAD_SETTING", "Fuseau horaire invalide (ex : Europe/Paris)."); }
            long now = System.currentTimeMillis();
            Settings.set(c, Settings.TAX_ZONE, z, admin.id().toString(), now);
            Records.audit(c, admin.id(), "SETTING_ZONE", "settings", Settings.TAX_ZONE, z, now);
            return null;
        });
    }

    public CompletableFuture<Void> setBackupInterval(Actor admin, int minutes) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            if (minutes < 10 || minutes > 10_080) throw new EconomyException("BAD_SETTING", "Intervalle de sauvegarde : 10 à 10080 minutes.");
            long now = System.currentTimeMillis();
            Settings.set(c, EconomyScheduler.BACKUP_INTERVAL, Integer.toString(minutes), admin.id().toString(), now);
            Records.audit(c, admin.id(), "SETTING_BACKUP", "settings", EconomyScheduler.BACKUP_INTERVAL, Integer.toString(minutes), now);
            return null;
        });
    }

    public CompletableFuture<Void> logAction(Actor admin, String action, String details) {
        return db.write(c -> { Records.audit(c, admin.id(), action, "admin", null, details, System.currentTimeMillis()); return null; });
    }
}
