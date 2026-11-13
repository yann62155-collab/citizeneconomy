package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acces de base partages : joueurs, entreprises, membres, audit, notifications. */
final class Records {
    private Records() { }

    static final String MEMBER_SELECT =
            "SELECT e.id, e.company_id, e.player_uuid, p.name, e.role, e.grade, e.salary, e.permissions, e.hired_at "
          + "FROM employees e JOIN players p ON p.uuid = e.player_uuid ";

    static final Sql.RowMapper<Member> MEMBER = rs -> new Member(rs.getLong(1), rs.getLong(2),
            UUID.fromString(rs.getString(3)), rs.getString(4), CompanyRole.valueOf(rs.getString(5)),
            rs.getString(6), rs.getLong(7), rs.getInt(8), rs.getLong(9));

    static final Sql.RowMapper<Company> COMPANY = rs -> {
        String mgr = rs.getString(9);
        return new Company(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getLong(7), UUID.fromString(rs.getString(8)), mgr == null ? null : UUID.fromString(mgr),
                CompanyStatus.valueOf(rs.getString(10)), rs.getString(11), rs.getInt(12) == 1, rs.getString(13));
    };

    static final String COMPANY_SELECT =
            "SELECT id, uid, name, emblem, description, sector, created_at, owner_uuid, manager_uuid, status, "
          + "status_reason, announcements_suspended, matricule FROM companies ";

    static void touchPlayer(Connection c, UUID id, String name, long now) throws SQLException {
        Sql.update(c, "INSERT INTO players(uuid,name,first_seen,last_seen) VALUES (?,?,?,?) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen", id, name, now, now);
    }

    static Company company(Connection c, long id) throws SQLException {
        return Sql.one(c, COMPANY_SELECT + "WHERE id = ?", COMPANY, id)
                .orElseThrow(() -> new EconomyException("NOT_FOUND", "Entreprise introuvable."));
    }

    static Optional<Member> activeMember(Connection c, long companyId, UUID player) throws SQLException {
        return Sql.one(c, MEMBER_SELECT + "WHERE e.company_id = ? AND e.player_uuid = ? AND e.fired_at IS NULL",
                MEMBER, companyId, player);
    }

    static List<Member> activeMembers(Connection c, long companyId) throws SQLException {
        return Sql.query(c, MEMBER_SELECT + "WHERE e.company_id = ? AND e.fired_at IS NULL "
                + "ORDER BY e.role DESC, p.name", MEMBER, companyId);
    }

    static void audit(Connection c, UUID actor, String action, String targetType, Object targetId, String details, long now)
            throws SQLException {
        Sql.update(c, "INSERT INTO audit_logs(actor_uuid,action,target_type,target_id,details,created_at) VALUES (?,?,?,?,?,?)",
                actor, action, targetType, targetId == null ? null : String.valueOf(targetId), details == null ? "" : details, now);
    }

    static void notify(Connection c, UUID player, String type, String message, long now) throws SQLException {
        Sql.update(c, "INSERT INTO notifications(player_uuid,type,message,created_at) VALUES (?,?,?,?)",
                player, type, message, now);
    }
}
