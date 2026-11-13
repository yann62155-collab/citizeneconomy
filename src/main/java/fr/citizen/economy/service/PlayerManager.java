package fr.citizen.economy.service;

import fr.citizen.economy.domain.Actor;
import fr.citizen.economy.persistence.Database;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Annuaire des joueurs connus (necessaire pour cibler un joueur par pseudo, meme hors ligne). */
public final class PlayerManager {
    public record PlayerRef(UUID uuid, String name) { }
    public record PlayerRow(UUID uuid, String name, long firstSeen, long lastSeen, long balance) { }

    private final Database db;

    public PlayerManager(Database db) { this.db = db; }

    public CompletableFuture<Void> touch(Actor a) {
        return db.write(c -> { Records.touchPlayer(c, a.id(), a.name(), System.currentTimeMillis()); return null; });
    }

    public CompletableFuture<Optional<PlayerRef>> findByUuid(UUID uuid) {
        return db.read(c -> Sql.one(c, "SELECT uuid, name FROM players WHERE uuid = ?",
                rs -> new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)), uuid.toString()));
    }

    public CompletableFuture<Optional<PlayerRef>> findByName(String name) {
        return db.read(c -> Sql.one(c, "SELECT uuid, name FROM players WHERE name = ? COLLATE NOCASE ORDER BY last_seen DESC LIMIT 1",
                rs -> new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)), name == null ? "" : name.trim()));
    }

    public CompletableFuture<List<PlayerRow>> list(Actor admin, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT p.uuid, p.name, p.first_seen, p.last_seen, COALESCE(a.balance,0) FROM players p "
                    + "LEFT JOIN personal_accounts a ON a.player_uuid = p.uuid ORDER BY p.name LIMIT ? OFFSET ?",
                    rs -> new PlayerRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                    Math.max(1, Math.min(limit, 100)), Math.max(0, offset));
        });
    }
}
