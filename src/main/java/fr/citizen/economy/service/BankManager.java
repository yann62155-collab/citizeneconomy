package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.PermissionManager.Authority;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Operations bancaires "joueur". L'identite (Actor) vient du serveur ; le txUuid est genere par le serveur a la
 * validation de l'ecran (un double clic reutilise le meme identifiant : l'argent ne bouge qu'une fois).
 *
 * Protocole pieces physiques (couche Minecraft, phase 8) :
 *  - depot   : retirer les pieces de l'inventaire (thread serveur) PUIS deposit(); si echec -> rendre les pieces ;
 *  - retrait : withdraw() PUIS donner les pieces (Coins.breakdown) ; inventaire plein -> pieces au sol / re-depot.
 */
public final class BankManager {
    /** Plafond par operation en especes (borne le nombre d'items manipules). Rendu configurable en phase 9. */
    public static final long MAX_CASH_OPERATION = 1_000_000L;

    private final Database db;

    public BankManager(Database db) { this.db = db; }

    // ------------------------------------------------------------------ compte personnel

    public CompletableFuture<Long> personalBalance(Actor a) {
        return db.read(c -> Sql.scalar(c, "SELECT balance FROM personal_accounts WHERE player_uuid = ?", a.id()));
    }

    public CompletableFuture<TxResult> deposit(Actor a, UUID txUuid, long amount) {
        return db.write(c -> {
            cashLimit(amount);
            Records.touchPlayer(c, a.id(), a.name(), System.currentTimeMillis());
            return TransactionManager.apply(c, new TxRequest(txUuid, TxType.DEPOSIT, amount, AccountRef.cash(), AccountRef.personal(a.id()), "Dépôt en espèces", a.id()));
        });
    }

    public CompletableFuture<TxResult> withdraw(Actor a, UUID txUuid, long amount) {
        return db.write(c -> {
            cashLimit(amount);
            return TransactionManager.apply(c, new TxRequest(txUuid, TxType.WITHDRAWAL, amount, AccountRef.personal(a.id()), AccountRef.cash(), "Retrait en espèces", a.id()));
        });
    }

    public CompletableFuture<TxResult> transfer(Actor a, UUID txUuid, UUID to, long amount, String description) {
        return db.write(c -> {
            if (!Sql.exists(c, "SELECT 1 FROM players WHERE uuid = ?", to)) throw new EconomyException("TARGET_UNKNOWN", "Joueur destinataire inconnu.");
            String d = Validation.optional(description, 200, "Description");
            TxResult r = TransactionManager.apply(c, new TxRequest(txUuid, TxType.TRANSFER, amount, AccountRef.personal(a.id()), AccountRef.personal(to), d, a.id()));
            if (!r.replayed()) Records.notify(c, to, "TRANSFER_RECEIVED", "Vous avez reçu " + amount + " PO de " + a.name() + ".", System.currentTimeMillis());
            return r;
        });
    }

    public CompletableFuture<List<LedgerLine>> personalHistory(Actor a, int limit, int offset) {
        return db.read(c -> history(c, AccountRef.personal(a.id()), limit, offset));
    }

    // ------------------------------------------------------------------ compte professionnel

    public CompletableFuture<Long> businessBalance(Actor a, long companyId) {
        return db.read(c -> {
            PermissionManager.authorize(c, a, companyId, Permission.VIEW_FINANCES, false);
            return Sql.scalar(c, "SELECT balance FROM business_accounts WHERE company_id = ?", companyId);
        });
    }

    /** Compte personnel -> compte de l'entreprise (tout membre actif peut alimenter la caisse). */
    public CompletableFuture<TxResult> businessDeposit(Actor a, long companyId, UUID txUuid, long amount) {
        return db.write(c -> {
            PermissionManager.authorize(c, a, companyId, null, true);
            return TransactionManager.apply(c, new TxRequest(txUuid, TxType.TRANSFER, amount, AccountRef.personal(a.id()),
                    AccountRef.business(companyId), "Apport de " + a.name(), a.id()));
        });
    }

    /** Compte de l'entreprise -> compte personnel de l'auteur (MANAGE_FINANCES). */
    public CompletableFuture<TxResult> businessWithdraw(Actor a, long companyId, UUID txUuid, long amount) {
        return db.write(c -> {
            PermissionManager.authorize(c, a, companyId, Permission.MANAGE_FINANCES, true);
            return TransactionManager.apply(c, new TxRequest(txUuid, TxType.TRANSFER, amount, AccountRef.business(companyId),
                    AccountRef.personal(a.id()), "Retrait de caisse par " + a.name(), a.id()));
        });
    }

    /** Paiement depuis l'entreprise vers un joueur ou une autre entreprise (MANAGE_FINANCES). */
    public CompletableFuture<TxResult> businessPay(Actor a, long companyId, AccountRef target, UUID txUuid, long amount, String description) {
        return db.write(c -> {
            Authority au = PermissionManager.authorize(c, a, companyId, Permission.MANAGE_FINANCES, true);
            if (target == null || (target.type() != AccountType.PERSONAL && target.type() != AccountType.BUSINESS))
                throw new EconomyException("BAD_TX", "Destinataire invalide.");
            if (target.type() == AccountType.PERSONAL && !Sql.exists(c, "SELECT 1 FROM players WHERE uuid = ?", target.id()))
                throw new EconomyException("TARGET_UNKNOWN", "Joueur destinataire inconnu.");
            String d = Validation.optional(description, 200, "Description");
            TxResult r = TransactionManager.apply(c, new TxRequest(txUuid, TxType.TRANSFER, amount, AccountRef.business(companyId), target, d, a.id()));
            if (!r.replayed() && target.type() == AccountType.PERSONAL)
                Records.notify(c, UUID.fromString(target.id()), "TRANSFER_RECEIVED",
                        "Vous avez reçu " + amount + " PO de « " + au.company().name() + " ».", System.currentTimeMillis());
            return r;
        });
    }

    public CompletableFuture<List<LedgerLine>> businessHistory(Actor a, long companyId, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.authorize(c, a, companyId, Permission.VIEW_FINANCES, false);
            return history(c, AccountRef.business(companyId), limit, offset);
        });
    }

    public record CompanyStats(long revenue, long expenses, long profit) { }

    /** Chiffre d'affaires (factures encaissees) et benefice (CA - salaires - factures payees) depuis une date. */
    public CompletableFuture<CompanyStats> companyStats(Actor a, long companyId, long since) {
        return db.read(c -> {
            PermissionManager.authorize(c, a, companyId, Permission.VIEW_FINANCES, false);
            String id = Long.toString(companyId);
            long revenue = Sql.scalar(c, "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='INVOICE_PAYMENT' AND target_type='BUSINESS' AND target_id = ? AND created_at >= ?", id, since);
            long expenses = Sql.scalar(c, "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE source_type='BUSINESS' AND source_id = ? AND type IN ('SALARY','INVOICE_PAYMENT') AND created_at >= ?", id, since);
            return new CompanyStats(revenue, expenses, revenue - expenses);
        });
    }

    // ------------------------------------------------------------------ interne

    private static void cashLimit(long amount) {
        if (amount > MAX_CASH_OPERATION)
            throw new EconomyException("CASH_LIMIT", "Maximum " + MAX_CASH_OPERATION + " PO par opération en espèces.");
    }

    private static List<LedgerLine> history(java.sql.Connection c, AccountRef acct, int limit, int offset) throws java.sql.SQLException {
        int lim = Math.max(1, Math.min(limit, 100));
        int off = Math.max(0, offset);
        return Sql.query(c, "SELECT l.tx_id, t.type, l.delta, l.balance_after, t.description, l.created_at FROM ledger_entries l "
                + "JOIN transactions t ON t.id = l.tx_id WHERE l.account_type = ? AND l.account_id = ? ORDER BY l.id DESC LIMIT ? OFFSET ?",
                rs -> new LedgerLine(rs.getLong(1), TxType.valueOf(rs.getString(2)), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getLong(6)),
                acct.type(), acct.id(), lim, off);
    }
}
