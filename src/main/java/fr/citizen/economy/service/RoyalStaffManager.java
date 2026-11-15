package fr.citizen.economy.service;

import fr.citizen.economy.domain.Actor;
import fr.citizen.economy.persistence.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Employes de la couronne : seuls (avec les admins) a pouvoir realiser le recensement. Payes par la tresorerie royale. */
public final class RoyalStaffManager {
    public static final int MAX_TITLE = 40;
    public static final long MAX_SALARY = 1_000_000;

    public record Staff(UUID player, String name, String title, long salary, long hiredAt) { }

    private final Database db;

    public RoyalStaffManager(Database db) { this.db = db; }

    private static final Sql.RowMapper<Staff> STAFF = rs -> new Staff(UUID.fromString(rs.getString(1)), rs.getString(2),
            rs.getString(3), rs.getLong(4), rs.getLong(5));

    public static boolean isStaff(Connection c, UUID player) throws SQLException {
        return Sql.exists(c, "SELECT 1 FROM royal_staff WHERE player_uuid = ?", player);
    }

    public CompletableFuture<Boolean> isStaff(UUID player) { return db.read(c -> isStaff(c, player)); }

    public CompletableFuture<List<Staff>> list(Actor admin) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT s.player_uuid, p.name, s.title, s.salary, s.hired_at FROM royal_staff s JOIN players p ON p.uuid = s.player_uuid "
                    + "ORDER BY s.hired_at, p.name", STAFF);
        });
    }

    public CompletableFuture<Void> hire(Actor admin, UUID player, String name, String title, long salary) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            String t = title(title);
            checkSalary(salary);
            long now = System.currentTimeMillis();
            Records.touchPlayer(c, player, name, now);
            if (isStaff(c, player)) throw new EconomyException("ALREADY_STAFF", name + " est déjà employé de la couronne.");
            Sql.update(c, "INSERT INTO royal_staff(player_uuid,title,salary,hired_at) VALUES (?,?,?,?)", player, t, salary, now);
            Records.audit(c, admin.id(), "ROYAL_STAFF_HIRE", "PLAYER", player, t + ", " + salary + " PO", now);
            Records.notify(c, player, "ROYAL_STAFF", "Vous êtes maintenant employé de la couronne (" + t + "). Vous pouvez réaliser le recensement : /recensement.", now);
            return null;
        });
    }

    /** Titre vide = inchange ; salaire negatif = inchange. */
    public CompletableFuture<Void> update(Actor admin, UUID player, String title, long salary) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            Staff cur = Sql.one(c, "SELECT s.player_uuid, p.name, s.title, s.salary, s.hired_at FROM royal_staff s JOIN players p ON p.uuid = s.player_uuid WHERE s.player_uuid = ?", STAFF, player)
                    .orElseThrow(() -> new EconomyException("NOT_FOUND", "Cet employé de la couronne est introuvable."));
            String t = title == null || title.isBlank() ? cur.title() : title(title);
            long sal = salary < 0 ? cur.salary() : salary;
            checkSalary(sal);
            long now = System.currentTimeMillis();
            Sql.update(c, "UPDATE royal_staff SET title = ?, salary = ? WHERE player_uuid = ?", t, sal, player);
            Records.audit(c, admin.id(), "ROYAL_STAFF_EDIT", "PLAYER", player, t + ", " + sal + " PO", now);
            return null;
        });
    }

    public CompletableFuture<Void> fire(Actor admin, UUID player) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            long now = System.currentTimeMillis();
            if (Sql.update(c, "DELETE FROM royal_staff WHERE player_uuid = ?", player) == 0)
                throw new EconomyException("NOT_FOUND", "Cet employé de la couronne est introuvable.");
            Records.audit(c, admin.id(), "ROYAL_STAFF_FIRE", "PLAYER", player, "", now);
            Records.notify(c, player, "ROYAL_STAFF", "Vous n'êtes plus employé de la couronne.", now);
            return null;
        });
    }

    static String title(String s) { return Validation.text(s == null || s.isBlank() ? "Agent de la couronne" : s, 2, MAX_TITLE, "Titre"); }

    static void checkSalary(long salary) {
        if (salary < 0 || salary > MAX_SALARY) throw new EconomyException("BAD_AMOUNT", "Salaire invalide (0 à " + MAX_SALARY + " PO).");
    }
}
