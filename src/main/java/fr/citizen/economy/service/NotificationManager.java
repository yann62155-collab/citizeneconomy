package fr.citizen.economy.service;

import fr.citizen.economy.persistence.Database;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Historique et remise des notifications. Les notifications sont creees par les services (Records.notify). */
public final class NotificationManager {
    public record Notif(long id, String type, String message, long createdAt, boolean read) { }

    private final Database db;

    public NotificationManager(Database db) { this.db = db; }

    private static final Sql.RowMapper<Notif> MAPPER = rs -> new Notif(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getObject(5) != null);

    /** Joueurs ayant au moins une notification pas encore remise en jeu. */
    public CompletableFuture<List<UUID>> playersWithPending() {
        return db.read(c -> Sql.query(c, "SELECT DISTINCT player_uuid FROM notifications WHERE delivered_at IS NULL",
                rs -> UUID.fromString(rs.getString(1))));
    }

    public CompletableFuture<List<Notif>> undelivered(UUID player, int limit) {
        return db.read(c -> Sql.query(c, "SELECT id,type,message,created_at,read_at FROM notifications WHERE player_uuid = ? AND delivered_at IS NULL ORDER BY id LIMIT ?",
                MAPPER, player, Math.max(1, Math.min(limit, 50))));
    }

    public CompletableFuture<Void> markDelivered(List<Long> ids) {
        return db.write(c -> {
            long now = System.currentTimeMillis();
            for (long id : ids) Sql.update(c, "UPDATE notifications SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL", now, id);
            return null;
        });
    }

    public CompletableFuture<List<Notif>> history(UUID player, int limit, int offset) {
        return db.read(c -> Sql.query(c, "SELECT id,type,message,created_at,read_at FROM notifications WHERE player_uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, player, Math.max(1, Math.min(limit, 100)), Math.max(0, offset)));
    }

    public CompletableFuture<Long> unreadCount(UUID player) {
        return db.read(c -> Sql.scalar(c, "SELECT COUNT(*) FROM notifications WHERE player_uuid = ? AND read_at IS NULL", player));
    }

    public CompletableFuture<Void> markAllRead(UUID player) {
        return db.write(c -> {
            Sql.update(c, "UPDATE notifications SET read_at = ? WHERE player_uuid = ? AND read_at IS NULL", System.currentTimeMillis(), player);
            return null;
        });
    }

    /** Purge les notifications lues de plus de N jours (maintenance). */
    public CompletableFuture<Integer> purgeOld(int days) {
        return db.write(c -> Sql.update(c, "DELETE FROM notifications WHERE read_at IS NOT NULL AND created_at < ?",
                System.currentTimeMillis() - days * 86_400_000L));
    }
}
