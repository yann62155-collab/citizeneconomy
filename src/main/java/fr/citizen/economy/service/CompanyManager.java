package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Demandes de creation, creation effective, statuts administratifs. */
public final class CompanyManager {
    private static final int MAX_DESC = 500;

    private final Database db;

    public CompanyManager(Database db) { this.db = db; }

    private static final Sql.RowMapper<CompanyRequest> REQUEST = rs -> new CompanyRequest(rs.getLong(1),
            UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getString(7), RequestStatus.valueOf(rs.getString(8)), rs.getString(9), rs.getLong(10));

    private static final String REQUEST_SELECT =
            "SELECT r.id, r.applicant_uuid, p.name, r.name, r.description, r.sector, r.emblem, r.status, r.admin_note, r.created_at "
          + "FROM company_requests r JOIN players p ON p.uuid = r.applicant_uuid ";

    // ------------------------------------------------------------------ demandes

    public CompletableFuture<Long> submitRequest(Actor actor, String name, String description, String sector, String emblem) {
        return db.write(c -> {
            String n = Validation.companyName(name);
            String d = Validation.optional(description, MAX_DESC, "Description");
            String s = Validation.text(sector, 2, 32, "Secteur");
            String e = Validation.optional(emblem, 64, "Embleme");
            long now = System.currentTimeMillis();
            Records.touchPlayer(c, actor.id(), actor.name(), now);
            if (Sql.exists(c, "SELECT 1 FROM company_requests WHERE applicant_uuid = ? AND status IN ('PENDING','CHANGES_REQUESTED')", actor.id()))
                throw new EconomyException("REQUEST_EXISTS", "Vous avez déjà une demande en cours.");
            ensureNameFree(c, n, -1);
            long id = Sql.insert(c, "INSERT INTO company_requests(applicant_uuid,name,description,sector,emblem,status,created_at) "
                    + "VALUES (?,?,?,?,?, 'PENDING', ?)", actor.id(), n, d, s, e, now);
            Records.audit(c, actor.id(), "REQUEST_SUBMITTED", "company_request", id, n, now);
            return id;
        });
    }

    /** Le demandeur corrige sa demande apres un "modifications demandées" ; elle repasse en attente. */
    public CompletableFuture<Void> updateRequest(Actor actor, long requestId, String name, String description, String sector, String emblem) {
        return db.write(c -> {
            CompanyRequest r = request(c, requestId);
            if (!r.applicant().equals(actor.id())) throw new EconomyException("FORBIDDEN", "Ce n'est pas votre demande.");
            if (r.status() != RequestStatus.CHANGES_REQUESTED)
                throw new EconomyException("BAD_STATE", "Cette demande ne peut pas être modifiée.");
            String n = Validation.companyName(name);
            String d = Validation.optional(description, MAX_DESC, "Description");
            String s = Validation.text(sector, 2, 32, "Secteur");
            String e = Validation.optional(emblem, 64, "Embleme");
            ensureNameFree(c, n, requestId);
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE company_requests SET name=?, description=?, sector=?, emblem=?, status='PENDING', admin_note=NULL WHERE id=?",
                    n, d, s, e, requestId);
            Records.audit(c, actor.id(), "REQUEST_RESUBMITTED", "company_request", requestId, n, now);
            return null;
        });
    }

    public CompletableFuture<List<CompanyRequest>> pendingRequests(Actor admin) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, REQUEST_SELECT + "WHERE r.status = 'PENDING' ORDER BY r.created_at", REQUEST);
        });
    }

    public CompletableFuture<ApplicantInfo> applicantInfo(Actor admin, long requestId) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            CompanyRequest r = request(c, requestId);
            return Sql.one(c, "SELECT uuid, name, first_seen, last_seen FROM players WHERE uuid = ?", rs -> new ApplicantInfo(
                    UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4),
                    (int) Sql.scalar(c, "SELECT COUNT(*) FROM employees e JOIN companies co ON co.id = e.company_id "
                            + "WHERE e.player_uuid = ? AND e.fired_at IS NULL AND co.status IN ('ACTIVE','SUSPENDED')", r.applicant()),
                    (int) Sql.scalar(c, "SELECT COUNT(*) FROM company_requests WHERE applicant_uuid = ? AND status = 'REFUSED'", r.applicant())),
                    r.applicant()).orElseThrow(() -> new EconomyException("NOT_FOUND", "Joueur introuvable."));
        });
    }

    /** Accepte la demande : cree l'entreprise ACTIVE, son proprietaire et son compte professionnel, en une transaction. */
    public CompletableFuture<Long> accept(Actor admin, long requestId, String note) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            CompanyRequest r = request(c, requestId);
            if (r.status() != RequestStatus.PENDING) throw new EconomyException("BAD_STATE", "La demande n'est plus en attente.");
            long now = System.currentTimeMillis();
            long companyId;
            try {
                companyId = Sql.insert(c, "INSERT INTO companies(uid,name,emblem,description,sector,created_at,updated_at,owner_uuid,status) "
                        + "VALUES (?,?,?,?,?,?,?,?, 'ACTIVE')", UUID.randomUUID().toString(), r.name(),
                        r.emblem(), r.description(), r.sector(), now, now, r.applicant());
            } catch (SQLException e) {
                if (Sql.isUnique(e, "companies.name")) throw new EconomyException("NAME_TAKEN", "Ce nom d'entreprise est déjà pris.");
                throw e;
            }
            Sql.update(c, "UPDATE companies SET matricule = ? WHERE id = ?", Company.matriculeOf(companyId), companyId);
            Sql.insert(c, "INSERT INTO employees(company_id,player_uuid,role,grade,salary,permissions,hired_at) VALUES (?,?, 'OWNER','Propriétaire',0,?,?)",
                    companyId, r.applicant(), PermissionManager.ALL, now);
            Sql.insert(c, "INSERT INTO business_accounts(company_id,balance,created_at) VALUES (?,0,?)", companyId, now);
            Sql.update(c, "UPDATE company_requests SET status='ACCEPTED', admin_note=?, reviewed_by=?, reviewed_at=?, company_id=? WHERE id=?",
                    blankToNull(note), admin.id(), now, companyId, requestId);
            Records.audit(c, admin.id(), "REQUEST_ACCEPTED", "company", companyId, r.name(), now);
            Records.notify(c, r.applicant(), "COMPANY_CREATED", "Votre entreprise « " + r.name() + " » a été créée.", now);
            return companyId;
        });
    }

    public CompletableFuture<Void> refuse(Actor admin, long requestId, String note) {
        return decide(admin, requestId, RequestStatus.REFUSED, note, "REQUEST_REFUSED", "COMPANY_REFUSED", "Votre demande pour « %s » a été refusée.");
    }

    public CompletableFuture<Void> requestChanges(Actor admin, long requestId, String note) {
        return decide(admin, requestId, RequestStatus.CHANGES_REQUESTED, note, "REQUEST_CHANGES", "COMPANY_CHANGES",
                "Des modifications sont demandées pour « %s ». Consultez la note de l'administration.");
    }

    private CompletableFuture<Void> decide(Actor admin, long requestId, RequestStatus status, String note,
                                           String auditAction, String notifType, String msgFormat) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            CompanyRequest r = request(c, requestId);
            if (r.status() != RequestStatus.PENDING) throw new EconomyException("BAD_STATE", "La demande n'est plus en attente.");
            String n = Validation.text(note, 3, 500, "Motif");
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE company_requests SET status=?, admin_note=?, reviewed_by=?, reviewed_at=? WHERE id=?",
                    status, n, admin.id(), now, requestId);
            Records.audit(c, admin.id(), auditAction, "company_request", requestId, n, now);
            Records.notify(c, r.applicant(), notifType, String.format(msgFormat, r.name()) + " Motif : " + n, now);
            return null;
        });
    }

    // ------------------------------------------------------------------ statut administratif

    /** ACTIVE <-> SUSPENDED, puis CLOSED (definitif). Une entreprise ne peut etre fermee qu'avec un compte a 0. */
    public CompletableFuture<Void> setStatus(Actor admin, long companyId, CompanyStatus target, String reason) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            Company co = Records.company(c, companyId);
            boolean ok = switch (co.status()) {
                case ACTIVE -> target == CompanyStatus.SUSPENDED || target == CompanyStatus.CLOSED;
                case SUSPENDED -> target == CompanyStatus.ACTIVE || target == CompanyStatus.CLOSED;
                case PENDING, CLOSED -> false;
            };
            if (!ok) throw new EconomyException("BAD_STATE", "Transition " + co.status() + " -> " + target + " impossible.");
            String why = Validation.text(reason, 3, 300, "Motif");
            long now = System.currentTimeMillis();
            if (target == CompanyStatus.CLOSED) {
                long balance = Sql.scalar(c, "SELECT balance FROM business_accounts WHERE company_id = ?", companyId);
                if (balance != 0)
                    throw new EconomyException("BALANCE_NOT_ZERO", "Le compte professionnel doit être à 0 avant la fermeture (solde : " + balance + ").");
            }
            List<Member> members = Records.activeMembers(c, companyId);
            Sql.update(c, "UPDATE companies SET status=?, status_reason=?, updated_at=? WHERE id=?", target, why, now, companyId);
            if (target == CompanyStatus.CLOSED) Sql.update(c, "UPDATE employees SET fired_at=? WHERE company_id=? AND fired_at IS NULL", now, companyId);
            Records.audit(c, admin.id(), "COMPANY_" + target.name(), "company", companyId, why, now);
            String msg = switch (target) {
                case SUSPENDED -> "L'entreprise « " + co.name() + " » a été suspendue. Motif : " + why;
                case ACTIVE -> "L'entreprise « " + co.name() + " » est de nouveau active.";
                default -> "L'entreprise « " + co.name() + " » a été fermée. Motif : " + why;
            };
            for (Member m : members) Records.notify(c, m.player(), "COMPANY_" + target.name(), msg, now);
            return null;
        });
    }

    // ------------------------------------------------------------------ lecture

    public CompletableFuture<Company> get(Actor actor, long companyId) {
        return db.read(c -> {
            Company co = Records.company(c, companyId);
            if (!actor.admin() && !actor.isCouncil() && Records.activeMember(c, companyId, actor.id()).isEmpty())
                throw new EconomyException("FORBIDDEN", "Vous ne faites pas partie de cette entreprise.");
            return co;
        });
    }

    public CompletableFuture<List<Company>> companiesOf(UUID player) {
        return db.read(c -> Sql.query(c, Records.COMPANY_SELECT + "WHERE id IN (SELECT company_id FROM employees "
                + "WHERE player_uuid = ? AND fired_at IS NULL) AND status IN ('ACTIVE','SUSPENDED') ORDER BY name", Records.COMPANY, player));
    }

    public CompletableFuture<List<Company>> listAll(Actor admin) {
        return db.read(c -> {
            if (!admin.isCouncil()) PermissionManager.requireAdmin(admin);
            return Sql.query(c, Records.COMPANY_SELECT + "ORDER BY name", Records.COMPANY);
        });
    }

    public CompletableFuture<List<CompanyRequest>> myRequests(UUID applicant) {
        return db.read(c -> Sql.query(c, REQUEST_SELECT + "WHERE r.applicant_uuid = ? ORDER BY r.id DESC LIMIT 5", REQUEST, applicant));
    }

    /** Entreprise ACTIVE retrouvee par son nom (insensible a la casse) ou, a defaut, par son matricule (E-0001, 0001, 1). */
    public CompletableFuture<java.util.Optional<Company>> findActiveByName(String name) {
        return db.read(c -> {
            String key = name == null ? "" : name.trim();
            if (key.startsWith("@")) key = key.substring(1).trim();
            var byName = Sql.one(c, Records.COMPANY_SELECT + "WHERE name = ? COLLATE NOCASE AND status = 'ACTIVE'", Records.COMPANY, key);
            if (byName.isPresent()) return byName;
            String mat = Company.parseMatricule(key);
            if (mat == null) return byName;
            return Sql.one(c, Records.COMPANY_SELECT + "WHERE matricule = ? AND status = 'ACTIVE'", Records.COMPANY, mat);
        });
    }

    // ------------------------------------------------------------------ interne

    private static CompanyRequest request(java.sql.Connection c, long id) throws SQLException {
        return Sql.one(c, REQUEST_SELECT + "WHERE r.id = ?", REQUEST, id)
                .orElseThrow(() -> new EconomyException("NOT_FOUND", "Demande introuvable."));
    }

    private static void ensureNameFree(java.sql.Connection c, String name, long ignoreRequestId) throws SQLException {
        if (Sql.exists(c, "SELECT 1 FROM companies WHERE name = ?", name))
            throw new EconomyException("NAME_TAKEN", "Ce nom d'entreprise est déjà pris.");
        if (Sql.exists(c, "SELECT 1 FROM company_requests WHERE name = ? COLLATE NOCASE AND status IN ('PENDING','CHANGES_REQUESTED') AND id <> ?",
                name, ignoreRequestId))
            throw new EconomyException("NAME_TAKEN", "Une demande est déjà en cours avec ce nom.");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
