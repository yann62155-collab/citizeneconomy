package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.PermissionManager.Authority;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Recrutement, licenciement, grades, salaires, permissions, hierarchie. */
public final class EmployeeManager {
    private final Database db;

    public EmployeeManager(Database db) { this.db = db; }

    public CompletableFuture<Long> hire(Actor actor, long companyId, UUID target, String targetName, long salary) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, Permission.MANAGE_EMPLOYEES, true);
            if (a.company().status() != CompanyStatus.ACTIVE) throw new EconomyException("COMPANY_NOT_ACTIVE", "L'entreprise n'est pas active.");
            long sal = Validation.salary(salary);
            if (Records.activeMember(c, companyId, target).isPresent())
                throw new EconomyException("ALREADY_MEMBER", "Ce joueur est déjà membre de l'entreprise.");
            if (a.member() != null && a.member().role() == CompanyRole.MANAGER && sal > 0 && !PermissionManager.has(a.member(), Permission.MANAGE_SALARIES))
                throw new EconomyException("FORBIDDEN", "Vous ne pouvez pas définir de salaire.");
            long now = System.currentTimeMillis();
            Records.touchPlayer(c, target, targetName, now);
            long empId = Sql.insert(c, "INSERT INTO employees(company_id,player_uuid,role,grade,salary,permissions,hired_at) VALUES (?,?, 'EMPLOYEE','Employé',?,?,?)",
                    companyId, target, sal, PermissionManager.defaultsFor(CompanyRole.EMPLOYEE), now);
            Sql.update(c, "INSERT INTO employee_salary_history(employee_id,old_salary,new_salary,changed_by,changed_at) VALUES (?,0,?,?,?)",
                    empId, sal, actor.id().toString(), now);
            Records.audit(c, actor.id(), "EMPLOYEE_HIRED", "company", companyId, target + " salaire=" + sal, now);
            Records.notify(c, target, "HIRED", "Vous avez été recruté par « " + a.company().name() + " ».", now);
            return empId;
        });
    }

    public CompletableFuture<Void> fire(Actor actor, long companyId, UUID target) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, Permission.MANAGE_EMPLOYEES, true);
            Member t = target(c, companyId, target);
            if (t.role() == CompanyRole.OWNER) throw new EconomyException("FORBIDDEN", "Le propriétaire ne peut pas être licencié.");
            if (t.player().equals(actor.id()) && !a.admin()) throw new EconomyException("FORBIDDEN", "Vous ne pouvez pas vous licencier vous-même.");
            if (!a.canManage(t)) throw new EconomyException("FORBIDDEN", "Vous n'avez pas autorité sur ce membre.");
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE employees SET fired_at=? WHERE id=?", now, t.employeeId());
            if (t.role() == CompanyRole.MANAGER) Sql.update(c, "UPDATE companies SET manager_uuid=NULL, updated_at=? WHERE id=?", now, companyId);
            Records.audit(c, actor.id(), "EMPLOYEE_FIRED", "company", companyId, target.toString(), now);
            Records.notify(c, target, "FIRED", "Vous avez été licencié de « " + a.company().name() + " ».", now);
            return null;
        });
    }

    public CompletableFuture<Void> setGrade(Actor actor, long companyId, UUID target, String grade) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, Permission.MANAGE_EMPLOYEES, true);
            Member t = target(c, companyId, target);
            if (!a.canManage(t)) throw new EconomyException("FORBIDDEN", "Vous n'avez pas autorité sur ce membre.");
            String g = Validation.text(grade, 2, 32, "Grade");
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE employees SET grade=? WHERE id=?", g, t.employeeId());
            Records.audit(c, actor.id(), "EMPLOYEE_GRADE", "company", companyId, target + " -> " + g, now);
            return null;
        });
    }

    public CompletableFuture<Void> setSalary(Actor actor, long companyId, UUID target, long salary) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, Permission.MANAGE_SALARIES, true);
            Member t = target(c, companyId, target);
            if (!a.canManage(t)) throw new EconomyException("FORBIDDEN", "Vous n'avez pas autorité sur ce membre.");
            long sal = Validation.salary(salary);
            if (sal == t.salary()) return null;
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE employees SET salary=? WHERE id=?", sal, t.employeeId());
            Sql.update(c, "INSERT INTO employee_salary_history(employee_id,old_salary,new_salary,changed_by,changed_at) VALUES (?,?,?,?,?)",
                    t.employeeId(), t.salary(), sal, actor.id().toString(), now);
            Records.audit(c, actor.id(), "SALARY_CHANGED", "company", companyId, target + " " + t.salary() + " -> " + sal, now);
            Records.notify(c, target, "SALARY_CHANGED", "Votre salaire chez « " + a.company().name() + " » est maintenant de " + sal + " PO.", now);
            return null;
        });
    }

    /** Definit les permissions d'un membre. Impossible d'accorder ce qu'on ne detient pas soi-meme ou une permission reservee aux gerants a un employe. */
    public CompletableFuture<Void> setPermissions(Actor actor, long companyId, UUID target, int mask) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, Permission.MANAGE_PERMISSIONS, true);
            Member t = target(c, companyId, target);
            if (t.role() == CompanyRole.OWNER) throw new EconomyException("FORBIDDEN", "Les droits du propriétaire ne sont pas modifiables.");
            if (!a.canManage(t)) throw new EconomyException("FORBIDDEN", "Vous n'avez pas autorité sur ce membre.");
            int clean = PermissionManager.sanitize(t.role(), mask);
            if (!a.admin() && a.member().role() != CompanyRole.OWNER && (clean & ~a.member().permissions()) != 0)
                throw new EconomyException("FORBIDDEN", "Vous ne pouvez pas accorder des droits que vous n'avez pas.");
            Sql.update(c, "UPDATE employees SET permissions=? WHERE id=?", clean, t.employeeId());
            Records.audit(c, actor.id(), "PERMISSIONS_CHANGED", "company", companyId, target + " mask=" + clean, System.currentTimeMillis());
            return null;
        });
    }

    /** Nomme un gerant (proprietaire ou admin uniquement). L'ancien gerant redevient employe. */
    public CompletableFuture<Void> promoteToManager(Actor actor, long companyId, UUID target) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, null, true);
            if (!a.isOwnerOrAdmin()) throw new EconomyException("FORBIDDEN", "Seul le propriétaire peut nommer un gérant.");
            Member t = target(c, companyId, target);
            if (t.role() != CompanyRole.EMPLOYEE) throw new EconomyException("BAD_STATE", "Ce membre est déjà gérant ou propriétaire.");
            long now = System.currentTimeMillis();
            Records.activeMembers(c, companyId).stream().filter(m -> m.role() == CompanyRole.MANAGER).forEach(m -> {
                try {
                    Sql.update(c, "UPDATE employees SET role='EMPLOYEE', permissions=? WHERE id=?",
                            PermissionManager.defaultsFor(CompanyRole.EMPLOYEE), m.employeeId());
                } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
            });
            Sql.update(c, "UPDATE employees SET role='MANAGER', grade='Gérant', permissions=? WHERE id=?",
                    PermissionManager.defaultsFor(CompanyRole.MANAGER), t.employeeId());
            Sql.update(c, "UPDATE companies SET manager_uuid=?, updated_at=? WHERE id=?", target, now, companyId);
            Records.audit(c, actor.id(), "MANAGER_APPOINTED", "company", companyId, target.toString(), now);
            Records.notify(c, target, "MANAGER", "Vous êtes nommé gérant de « " + a.company().name() + " ».", now);
            return null;
        });
    }

    public CompletableFuture<Void> demoteManager(Actor actor, long companyId) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, null, true);
            if (!a.isOwnerOrAdmin()) throw new EconomyException("FORBIDDEN", "Seul le propriétaire peut retirer le gérant.");
            Member mgr = Records.activeMembers(c, companyId).stream().filter(m -> m.role() == CompanyRole.MANAGER).findFirst()
                    .orElseThrow(() -> new EconomyException("BAD_STATE", "Aucun gérant à retirer."));
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE employees SET role='EMPLOYEE', grade='Employé', permissions=? WHERE id=?",
                    PermissionManager.defaultsFor(CompanyRole.EMPLOYEE), mgr.employeeId());
            Sql.update(c, "UPDATE companies SET manager_uuid=NULL, updated_at=? WHERE id=?", now, companyId);
            Records.audit(c, actor.id(), "MANAGER_REMOVED", "company", companyId, mgr.player().toString(), now);
            return null;
        });
    }

    // ------------------------------------------------------------------ lecture

    public CompletableFuture<List<Member>> members(Actor actor, long companyId) {
        return db.read(c -> {
            PermissionManager.authorize(c, actor, companyId, null, false);
            return Records.activeMembers(c, companyId);
        });
    }

    /** Historique salarial : reserve aux detenteurs de MANAGE_SALARIES, ou a l'employe concerne. */
    public CompletableFuture<List<SalaryChange>> salaryHistory(Actor actor, long companyId, UUID target) {
        return db.read(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, null, false);
            boolean self = actor.id().equals(target);
            if (!self && !a.admin() && !PermissionManager.has(a.member(), Permission.MANAGE_SALARIES))
                throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
            Member t = Sql.one(c, Records.MEMBER_SELECT + "WHERE e.company_id = ? AND e.player_uuid = ? ORDER BY e.id DESC LIMIT 1",
                    Records.MEMBER, companyId, target).orElseThrow(() -> new EconomyException("NOT_FOUND", "Membre introuvable."));
            return Sql.query(c, "SELECT old_salary, new_salary, changed_by, changed_at FROM employee_salary_history "
                    + "WHERE employee_id = ? ORDER BY id", rs -> new SalaryChange(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)),
                    t.employeeId());
        });
    }

    private static Member target(java.sql.Connection c, long companyId, UUID target) throws java.sql.SQLException {
        return Records.activeMember(c, companyId, target)
                .orElseThrow(() -> new EconomyException("NOT_FOUND", "Ce joueur n'est pas membre de l'entreprise."));
    }
}
