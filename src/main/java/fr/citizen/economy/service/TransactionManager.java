package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Unique point de passage de tout mouvement d'argent. Garanties :
 *  - atomicite : debit + credit + transaction + grand livre dans UNE transaction SQL ;
 *  - jamais de solde negatif (UPDATE conditionnel + CHECK en base) ;
 *  - idempotence : tx_uuid UNIQUE, rejouer renvoie le resultat initial ;
 *  - serialisation : toutes les ecritures passent par le thread ecrivain unique de Database.
 */
public final class TransactionManager {
    public static final long MAX_BALANCE = 4_000_000_000_000_000L;

    private final Database db;

    public TransactionManager(Database db) { this.db = db; }

    public CompletableFuture<TxResult> execute(TxRequest r) {
        return db.write(c -> apply(c, r));
    }

    /** A appeler UNIQUEMENT depuis une lambda Database.write (transaction deja ouverte). */
    public static TxResult apply(Connection c, TxRequest r) throws SQLException {
        validate(r);
        Optional<TxResult> replay = replay(c, r);
        if (replay.isPresent()) return replay.get();

        long now = System.currentTimeMillis();
        long srcAfter = debit(c, r.source(), r.amount(), now);
        long tgtAfter = credit(c, r.target(), r.amount(), now);

        long id = Sql.insert(c, "INSERT INTO transactions(tx_uuid,type,taxable,amount,source_type,source_id,target_type,target_id,"
                        + "description,initiator_uuid,invoice_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                r.txUuid().toString(), r.type(), r.type().taxable(), r.amount(),
                r.source().type(), r.source().id(), r.target().type(), r.target().id(),
                r.description() == null ? "" : r.description(), r.initiator(), r.invoiceId(), now);
        if (r.source().type() != AccountType.CASH) ledger(c, id, r.source(), -r.amount(), srcAfter, now);
        if (r.target().type() != AccountType.CASH) ledger(c, id, r.target(), r.amount(), tgtAfter, now);
        return new TxResult(id, r.txUuid(), srcAfter, tgtAfter, false);
    }

    // ------------------------------------------------------------------ validation

    private static void validate(TxRequest r) {
        if (r.txUuid() == null || r.type() == null || r.source() == null || r.target() == null)
            throw new EconomyException("BAD_TX", "Transaction incomplète.");
        if (r.amount() <= 0 || r.amount() > Validation.MAX_AMOUNT)
            throw new EconomyException("BAD_AMOUNT", "Montant invalide.");
        if (r.source().equals(r.target())) throw new EconomyException("BAD_TX", "Source et destination identiques.");
        if (r.description() != null && r.description().length() > 200)
            throw new EconomyException("BAD_TEXT", "Description trop longue (200 max).");
        if (!allowed(r.type(), r.source().type(), r.target().type()))
            throw new EconomyException("BAD_TX", "Combinaison non autorisée : " + r.type() + " " + r.source().type() + " -> " + r.target().type());
    }

    private static boolean allowed(TxType t, AccountType s, AccountType d) {
        boolean sAcct = s == AccountType.PERSONAL || s == AccountType.BUSINESS;
        boolean dAcct = d == AccountType.PERSONAL || d == AccountType.BUSINESS;
        return switch (t) {
            case DEPOSIT -> s == AccountType.CASH && d == AccountType.PERSONAL;
            case WITHDRAWAL -> s == AccountType.PERSONAL && d == AccountType.CASH;
            case TRANSFER -> sAcct && dAcct;
            case SALARY -> s == AccountType.BUSINESS && d == AccountType.PERSONAL;
            case INVOICE_PAYMENT -> sAcct && d == AccountType.BUSINESS;
            case TAX -> sAcct && d == AccountType.TREASURY;
            case ROYAL_EXPENSE -> s == AccountType.TREASURY && (dAcct || d == AccountType.CASH);
            case ROYAL_REVENUE -> s == AccountType.CASH && d == AccountType.TREASURY;
            case ADJUSTMENT -> s != d;
        };
    }

    // ------------------------------------------------------------------ idempotence

    private record Existing(long id, TxType type, long amount, String st, String sid, String tt, String tid) { }

    private static Optional<TxResult> replay(Connection c, TxRequest r) throws SQLException {
        Optional<Existing> ex = Sql.one(c, "SELECT id,type,amount,source_type,source_id,target_type,target_id FROM transactions WHERE tx_uuid = ?",
                rs -> new Existing(rs.getLong(1), TxType.valueOf(rs.getString(2)), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)), r.txUuid().toString());
        if (ex.isEmpty()) return Optional.empty();
        Existing e = ex.get();
        boolean same = e.type() == r.type() && e.amount() == r.amount()
                && e.st().equals(r.source().type().name()) && java.util.Objects.equals(e.sid(), r.source().id())
                && e.tt().equals(r.target().type().name()) && java.util.Objects.equals(e.tid(), r.target().id());
        if (!same) throw new EconomyException("TX_CONFLICT", "Cet identifiant de transaction est déjà utilisé pour une autre opération.");
        long[] bal = {-1, -1};
        Sql.query(c, "SELECT delta, balance_after FROM ledger_entries WHERE tx_id = ?", rs -> {
            if (rs.getLong(1) < 0) bal[0] = rs.getLong(2); else bal[1] = rs.getLong(2);
            return 1;
        }, e.id());
        return Optional.of(new TxResult(e.id(), r.txUuid(), bal[0], bal[1], true));
    }

    // ------------------------------------------------------------------ debit / credit

    private static long debit(Connection c, AccountRef a, long amount, long now) throws SQLException {
        int rows;
        switch (a.type()) {
            case CASH -> { return -1; }
            case PERSONAL -> rows = Sql.update(c, "UPDATE personal_accounts SET balance = balance - ? WHERE player_uuid = ? AND balance >= ?",
                    amount, a.id(), amount);
            case BUSINESS -> {
                long companyId = Long.parseLong(a.id());
                String status = Sql.one(c, "SELECT status FROM companies WHERE id = ?", rs -> rs.getString(1), companyId)
                        .orElseThrow(() -> new EconomyException("NOT_FOUND", "Entreprise introuvable."));
                if (!status.equals("ACTIVE")) throw new EconomyException("COMPANY_NOT_ACTIVE", "L'entreprise n'est pas active : dépenses impossibles.");
                rows = Sql.update(c, "UPDATE business_accounts SET balance = balance - ? WHERE company_id = ? AND balance >= ?",
                        amount, companyId, amount);
            }
            case TREASURY -> rows = Sql.update(c, "UPDATE treasury SET balance = balance - ?, updated_at = ? WHERE id = 1 AND balance >= ?",
                    amount, now, amount);
            default -> throw new IllegalStateException();
        }
        if (rows != 1) throw new EconomyException("INSUFFICIENT_FUNDS", "Solde insuffisant.");
        return balance(c, a);
    }

    private static long credit(Connection c, AccountRef a, long amount, long now) throws SQLException {
        int rows;
        long limit = MAX_BALANCE - amount;
        switch (a.type()) {
            case CASH -> { return -1; }
            case PERSONAL -> {
                try {
                    Sql.update(c, "INSERT OR IGNORE INTO personal_accounts(player_uuid,balance,created_at) VALUES (?,0,?)", a.id(), now);
                } catch (SQLException e) {
                    if (e.getMessage() != null && e.getMessage().contains("FOREIGN KEY"))
                        throw new EconomyException("TARGET_UNKNOWN", "Joueur destinataire inconnu.");
                    throw e;
                }
                rows = Sql.update(c, "UPDATE personal_accounts SET balance = balance + ? WHERE player_uuid = ? AND balance <= ?", amount, a.id(), limit);
            }
            case BUSINESS -> {
                long companyId = Long.parseLong(a.id());
                String status = Sql.one(c, "SELECT status FROM companies WHERE id = ?", rs -> rs.getString(1), companyId)
                        .orElseThrow(() -> new EconomyException("NOT_FOUND", "Entreprise introuvable."));
                if (status.equals("CLOSED") || status.equals("PENDING"))
                    throw new EconomyException("COMPANY_NOT_ACTIVE", "Cette entreprise ne peut pas recevoir de fonds.");
                rows = Sql.update(c, "UPDATE business_accounts SET balance = balance + ? WHERE company_id = ? AND balance <= ?", amount, companyId, limit);
            }
            case TREASURY -> rows = Sql.update(c, "UPDATE treasury SET balance = balance + ?, updated_at = ? WHERE id = 1 AND balance <= ?", amount, now, limit);
            default -> throw new IllegalStateException();
        }
        if (rows != 1) throw new EconomyException("BALANCE_LIMIT", "Plafond de solde atteint.");
        return balance(c, a);
    }

    private static long balance(Connection c, AccountRef a) throws SQLException {
        return switch (a.type()) {
            case PERSONAL -> Sql.scalar(c, "SELECT balance FROM personal_accounts WHERE player_uuid = ?", a.id());
            case BUSINESS -> Sql.scalar(c, "SELECT balance FROM business_accounts WHERE company_id = ?", Long.parseLong(a.id()));
            case TREASURY -> Sql.scalar(c, "SELECT balance FROM treasury WHERE id = 1");
            case CASH -> -1;
        };
    }

    private static void ledger(Connection c, long txId, AccountRef a, long delta, long after, long now) throws SQLException {
        Sql.update(c, "INSERT INTO ledger_entries(tx_id,account_type,account_id,delta,balance_after,created_at) VALUES (?,?,?,?,?,?)",
                txId, a.type(), a.id(), delta, after, now);
    }

    // ------------------------------------------------------------------ verification d'integrite

    /** Compare chaque solde a la somme de ses ecritures du grand livre. Liste vide = tout est coherent. */
    public CompletableFuture<List<String>> verifyBalances() {
        return db.read(c -> {
            List<String> problems = new java.util.ArrayList<>();
            problems.addAll(Sql.query(c, "SELECT 'PERSONAL ' || p.player_uuid || ' solde=' || p.balance || ' ledger=' || COALESCE(s.sm,0) "
                    + "FROM personal_accounts p LEFT JOIN (SELECT account_id, SUM(delta) sm FROM ledger_entries WHERE account_type='PERSONAL' GROUP BY account_id) s "
                    + "ON s.account_id = p.player_uuid WHERE p.balance <> COALESCE(s.sm,0)", rs -> rs.getString(1)));
            problems.addAll(Sql.query(c, "SELECT 'BUSINESS ' || b.company_id || ' solde=' || b.balance || ' ledger=' || COALESCE(s.sm,0) "
                    + "FROM business_accounts b LEFT JOIN (SELECT account_id, SUM(delta) sm FROM ledger_entries WHERE account_type='BUSINESS' GROUP BY account_id) s "
                    + "ON s.account_id = CAST(b.company_id AS TEXT) WHERE b.balance <> COALESCE(s.sm,0)", rs -> rs.getString(1)));
            problems.addAll(Sql.query(c, "SELECT 'TREASURY solde=' || t.balance || ' ledger=' || COALESCE((SELECT SUM(delta) FROM ledger_entries WHERE account_type='TREASURY'),0) "
                    + "FROM treasury t WHERE t.balance <> COALESCE((SELECT SUM(delta) FROM ledger_entries WHERE account_type='TREASURY'),0)", rs -> rs.getString(1)));
            return problems;
        });
    }

    public static UUID newTxId() { return UUID.randomUUID(); }
}
