package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Tresorerie royale ("entreprise du Royaume") : compte unique, sans proprietaire ni employes, qui recoit les taxes.
 * Toutes les operations sont reservees a l'administration et inscrites au journal administratif.
 */
public final class TreasuryManager {
    private final Database db;

    public TreasuryManager(Database db) { this.db = db; }

    public CompletableFuture<Long> balance(Actor admin) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.scalar(c, "SELECT balance FROM treasury WHERE id = 1");
        });
    }

    /** Depense royale vers un joueur, une entreprise ou en especes. Motif obligatoire. */
    public CompletableFuture<TxResult> spend(Actor admin, UUID txUuid, AccountRef target, long amount, String reason) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            String why = Validation.text(reason, 3, 200, "Motif");
            if (target == null || target.type() == AccountType.TREASURY) throw new EconomyException("BAD_TX", "Destinataire invalide.");
            if (target.type() == AccountType.PERSONAL && !Sql.exists(c, "SELECT 1 FROM players WHERE uuid = ?", target.id()))
                throw new EconomyException("TARGET_UNKNOWN", "Joueur destinataire inconnu.");
            TxResult r = TransactionManager.apply(c, new TxRequest(txUuid, TxType.ROYAL_EXPENSE, amount, AccountRef.treasury(), target, why, admin.id()));
            if (!r.replayed()) {
                long now = System.currentTimeMillis();
                Records.audit(c, admin.id(), "ROYAL_EXPENSE", target.type().name().toLowerCase(), target.id(), amount + " PO : " + why, now);
                if (target.type() == AccountType.PERSONAL)
                    Records.notify(c, UUID.fromString(target.id()), "ROYAL_PAYMENT", "Versement royal de " + amount + " PO : " + why, now);
            }
            return r;
        });
    }

    /** Revenu administratif en especes (amendes, droits...). */
    public CompletableFuture<TxResult> addRevenue(Actor admin, UUID txUuid, long amount, String reason) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            String why = Validation.text(reason, 3, 200, "Motif");
            TxResult r = TransactionManager.apply(c, new TxRequest(txUuid, TxType.ROYAL_REVENUE, amount, AccountRef.cash(), AccountRef.treasury(), why, admin.id()));
            if (!r.replayed()) Records.audit(c, admin.id(), "ROYAL_REVENUE", "treasury", "1", amount + " PO : " + why, System.currentTimeMillis());
            return r;
        });
    }

    public CompletableFuture<List<LedgerLine>> history(Actor admin, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT l.tx_id, t.type, l.delta, l.balance_after, t.description, l.created_at FROM ledger_entries l "
                    + "JOIN transactions t ON t.id = l.tx_id WHERE l.account_type = 'TREASURY' ORDER BY l.id DESC LIMIT ? OFFSET ?",
                    rs -> new LedgerLine(rs.getLong(1), TxType.valueOf(rs.getString(2)), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getLong(6)),
                    Math.max(1, Math.min(limit, 100)), Math.max(0, offset));
        });
    }

    /** Statistiques : entrees et sorties par type d'operation sur une plage de dates. */
    public CompletableFuture<List<TreasuryStat>> stats(Actor admin, long from, long to) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT type, SUM(CASE WHEN target_type='TREASURY' THEN amount ELSE 0 END), "
                    + "SUM(CASE WHEN source_type='TREASURY' THEN amount ELSE 0 END) FROM transactions "
                    + "WHERE (source_type='TREASURY' OR target_type='TREASURY') AND created_at >= ? AND created_at < ? GROUP BY type ORDER BY type",
                    rs -> new TreasuryStat(TxType.valueOf(rs.getString(1)), rs.getLong(2), rs.getLong(3)), from, to);
        });
    }
}
