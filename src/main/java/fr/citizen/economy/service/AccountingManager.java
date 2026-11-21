package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Comptabilite d'une entreprise, calculee a partir des donnees reelles (grand livre bancaire, ventes, stock, taxes) :
 * rien n'est saisi deux fois et le resultat ne peut pas diverger de la banque. Seules les ecritures manuelles (loyer, aides...)
 * sont ajoutees a la main ; elles ne bougent jamais d'argent en banque.
 */
public final class AccountingManager {
    public static final int MAX_MANUAL = 5000;

    public record Report(long from, long to,
                         long salesRevenue, long salesCount, long otherRevenue, long manualIncome,
                         long costOfGoods, long salaries, long taxes, long invoicesPaid, long manualExpenses,
                         long bankBalance, long bankIn, long bankOut, long buybackTotal,
                         long stockValue, long reservedStock, long receivables, long taxDebt) {
        public long revenue() { return salesRevenue + otherRevenue + manualIncome; }
        public long expenses() { return costOfGoods + salaries + taxes + invoicesPaid + manualExpenses; }
        public long result() { return revenue() - expenses(); }
        public long grossMargin() { return salesRevenue - costOfGoods; }
        public long assets() { return bankBalance + stockValue + reservedStock + receivables; }
        public long liabilities() { return taxDebt; }
        public long equity() { return assets() - liabilities(); }
    }

    public record JournalEntry(long at, String label, String debit, String credit, long amount, String ref) { }

    public record TopProduct(String name, long qty, long revenue, long margin) { }

    public record Manual(long id, String kind, String category, String label, long amount, long entryAt, String createdBy) { }

    private final Database db;
    private final LongSupplier clock;

    public AccountingManager(Database db) { this(db, System::currentTimeMillis); }
    public AccountingManager(Database db, LongSupplier clock) { this.db = db; this.clock = clock; }

    private static void authorize(Connection c, Actor actor, long companyId, boolean mutating) throws SQLException {
        PermissionManager.Authority a = PermissionManager.authorize(c, actor, companyId, Permission.VIEW_ACCOUNTING, mutating);
        if (a.member() == null && !a.admin()) throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
    }

    // ------------------------------------------------------------------ rapport

    public CompletableFuture<Report> report(Actor actor, long companyId, long from, long to) {
        return db.read(c -> {
            authorize(c, actor, companyId, false);
            String id = Long.toString(companyId);
            long salesRevenue = Sql.scalar(c, "SELECT COALESCE(SUM(total),0) FROM sales WHERE company_id=? AND status='PAID' AND paid_at>=? AND paid_at<?", companyId, from, to);
            long salesCount = Sql.scalar(c, "SELECT COUNT(*) FROM sales WHERE company_id=? AND status='PAID' AND paid_at>=? AND paid_at<?", companyId, from, to);
            long cogs = Sql.scalar(c, "SELECT COALESCE(SUM(cost_total),0) FROM sales WHERE company_id=? AND status='PAID' AND paid_at>=? AND paid_at<?", companyId, from, to);
            long other = Sql.scalar(c, "SELECT COALESCE(SUM(t.amount),0) FROM transactions t WHERE t.type='INVOICE_PAYMENT' AND t.target_type='BUSINESS' AND t.target_id=? "
                    + "AND t.created_at>=? AND t.created_at<? AND (t.invoice_id IS NULL OR t.invoice_id NOT IN (SELECT invoice_id FROM sales WHERE invoice_id IS NOT NULL))", id, from, to);
            long salaries = outflow(c, id, "SALARY", from, to);
            long taxes = outflow(c, id, "TAX", from, to);
            long invPaid = outflow(c, id, "INVOICE_PAYMENT", from, to);
            long mIn = Sql.scalar(c, "SELECT COALESCE(SUM(amount),0) FROM manual_entries WHERE company_id=? AND kind='INCOME' AND deleted_at IS NULL AND entry_at>=? AND entry_at<?", companyId, from, to);
            long mOut = Sql.scalar(c, "SELECT COALESCE(SUM(amount),0) FROM manual_entries WHERE company_id=? AND kind='EXPENSE' AND deleted_at IS NULL AND entry_at>=? AND entry_at<?", companyId, from, to);
            long balance = Sql.scalar(c, "SELECT balance FROM business_accounts WHERE company_id = ?", companyId);
            long bankIn = Sql.scalar(c, "SELECT COALESCE(SUM(delta),0) FROM ledger_entries WHERE account_type='BUSINESS' AND account_id=? AND delta>0 AND created_at>=? AND created_at<?", id, from, to);
            long bankOut = -Sql.scalar(c, "SELECT COALESCE(SUM(delta),0) FROM ledger_entries WHERE account_type='BUSINESS' AND account_id=? AND delta<0 AND created_at>=? AND created_at<?", id, from, to);
            long buybacks = Sql.scalar(c, "SELECT COALESCE(SUM(total),0) FROM buybacks WHERE company_id=? AND created_at>=? AND created_at<?", companyId, from, to);
            long stock = Sql.scalar(c, "SELECT COALESCE(SUM(stock*cost),0) FROM products WHERE company_id=?", companyId);
            long reserved = Sql.scalar(c, "SELECT COALESCE(SUM(l.qty*l.unit_cost),0) FROM sale_lines l JOIN sales s ON s.id = l.sale_id WHERE s.company_id=? AND s.status='PENDING'", companyId);
            long receivables = Sql.scalar(c, "SELECT COALESCE(SUM(amount),0) FROM invoices WHERE issuer_company_id=? AND status IN ('PENDING','OVERDUE')", companyId);
            long taxDebt = Sql.scalar(c, "SELECT COALESCE(SUM(amount),0) FROM tax_history WHERE account_type='BUSINESS' AND account_id=? AND status='DUE'", id);
            return new Report(from, to, salesRevenue, salesCount, other, mIn, cogs, salaries, taxes, invPaid, mOut,
                    balance, bankIn, bankOut, buybacks, stock, reserved, receivables, taxDebt);
        });
    }

    private static long outflow(Connection c, String id, String type, long from, long to) throws SQLException {
        return Sql.scalar(c, "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type=? AND source_type='BUSINESS' AND source_id=? AND created_at>=? AND created_at<?", type, id, from, to);
    }

    public CompletableFuture<List<TopProduct>> topProducts(Actor actor, long companyId, long from, long to, int limit) {
        return db.read(c -> {
            authorize(c, actor, companyId, false);
            return Sql.query(c, "SELECT l.name, SUM(l.qty), SUM(l.qty*l.unit_price), SUM(l.qty*(l.unit_price-l.unit_cost)) FROM sale_lines l JOIN sales s ON s.id = l.sale_id "
                    + "WHERE s.company_id=? AND s.status='PAID' AND s.paid_at>=? AND s.paid_at<? GROUP BY l.product_id ORDER BY 3 DESC LIMIT ?",
                    rs -> new TopProduct(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), companyId, from, to, Math.max(1, Math.min(limit, 50)));
        });
    }

    // ------------------------------------------------------------------ journal (ecritures en partie double)

    public CompletableFuture<List<JournalEntry>> journal(Actor actor, long companyId, long from, long to, int limit) {
        return db.read(c -> {
            authorize(c, actor, companyId, false);
            String id = Long.toString(companyId);
            List<JournalEntry> out = new ArrayList<>();
            // ventes encaissees : produit + cout des ventes (sortie de stock)
            Sql.query(c, "SELECT number, customer_label, payment, total, cost_total, paid_at FROM sales WHERE company_id=? AND status='PAID' AND paid_at>=? AND paid_at<?", rs -> {
                String who = rs.getString(2);
                boolean cash = rs.getString(3).equals("CASH");
                long at = rs.getLong(6);
                out.add(new JournalEntry(at, "Vente " + rs.getString(1) + " (" + who + ")", cash ? "530 Caisse" : "512 Banque", "707 Ventes", rs.getLong(4), rs.getString(1)));
                if (rs.getLong(5) > 0) out.add(new JournalEntry(at, "Coût des ventes " + rs.getString(1), "603 Coût des marchandises vendues", "31 Stock", rs.getLong(5), rs.getString(1)));
                return 0;
            }, companyId, from, to);
            // entrees de marchandise, pertes et ecarts d'inventaire
            Sql.query(c, "SELECT m.created_at, m.delta, m.reason, m.unit_cost, p.name, m.note FROM stock_movements m JOIN products p ON p.id = m.product_id "
                    + "WHERE m.company_id=? AND m.reason IN ('RESTOCK','LOSS','ADJUST') AND (m.ref_type IS NULL OR m.ref_type <> 'buyback') AND m.created_at>=? AND m.created_at<?", rs -> {
                long delta = rs.getLong(2); long cost = Math.abs(delta) * rs.getLong(4);
                if (cost == 0) return 0;
                String name = rs.getString(5);
                switch (rs.getString(3)) {
                    case "RESTOCK" -> out.add(new JournalEntry(rs.getLong(1), "Achat de stock : " + delta + " × " + name, "31 Stock", "401 Fournisseurs", cost, "stock"));
                    case "LOSS" -> out.add(new JournalEntry(rs.getLong(1), "Perte / casse : " + (-delta) + " × " + name, "657 Pertes de stock", "31 Stock", cost, "stock"));
                    default -> out.add(delta > 0
                            ? new JournalEntry(rs.getLong(1), "Écart d'inventaire (+" + delta + ") " + name, "31 Stock", "603 Coût des marchandises vendues", cost, "stock")
                            : new JournalEntry(rs.getLong(1), "Écart d'inventaire (" + delta + ") " + name, "603 Coût des marchandises vendues", "31 Stock", cost, "stock"));
                }
                return 0;
            }, companyId, from, to);
            // rachats de marchandise : le stock entre, la banque paie le vendeur (facture de rachat)
            Sql.query(c, "SELECT b.created_at, b.number, b.qty, b.product_name, COALESCE(s.name,'?'), b.total FROM buybacks b LEFT JOIN players s ON s.uuid = b.seller_uuid "
                    + "WHERE b.company_id=? AND b.created_at>=? AND b.created_at<?", rs -> {
                out.add(new JournalEntry(rs.getLong(1), "Rachat " + rs.getString(2) + " : " + rs.getLong(3) + " × " + rs.getString(4) + " (" + rs.getString(5) + ")", "31 Stock", "512 Banque", rs.getLong(6), rs.getString(2)));
                return 0;
            }, companyId, from, to);
            // mouvements bancaires d'exploitation
            Sql.query(c, "SELECT created_at, type, amount, description, invoice_id, source_type, source_id, target_type, target_id FROM transactions "
                    + "WHERE ((source_type='BUSINESS' AND source_id=?) OR (target_type='BUSINESS' AND target_id=?)) AND type IN ('SALARY','TAX','INVOICE_PAYMENT') AND created_at>=? AND created_at<?", rs -> {
                boolean outgoing = "BUSINESS".equals(rs.getString(6)) && id.equals(rs.getString(7));
                long at = rs.getLong(1); long amount = rs.getLong(3); String desc = rs.getString(4);
                switch (rs.getString(2)) {
                    case "SALARY" -> { if (outgoing) out.add(new JournalEntry(at, "Salaire : " + desc, "641 Salaires", "512 Banque", amount, "salaire")); }
                    case "TAX" -> { if (outgoing) out.add(new JournalEntry(at, "Impôt : " + desc, "695 Impôts et taxes", "512 Banque", amount, "impôt")); }
                    default -> {
                        if (outgoing) out.add(new JournalEntry(at, "Facture payée : " + desc, "607 Achats et services", "512 Banque", amount, "facture"));
                        else if (rs.getObject(5) == null || !Sql.exists(c, "SELECT 1 FROM sales WHERE invoice_id = ?", rs.getLong(5)))
                            out.add(new JournalEntry(at, "Facture encaissée : " + desc, "512 Banque", "706 Prestations", amount, "facture"));
                    }
                }
                return 0;
            }, id, id, from, to);
            // ecritures manuelles
            Sql.query(c, "SELECT entry_at, kind, category, label, amount FROM manual_entries WHERE company_id=? AND deleted_at IS NULL AND entry_at>=? AND entry_at<?", rs -> {
                boolean inc = rs.getString(2).equals("INCOME");
                String cat = rs.getString(3);
                out.add(new JournalEntry(rs.getLong(1), (inc ? "Produit : " : "Charge : ") + rs.getString(4), inc ? "580 Hors banque" : "6 " + cat, inc ? "758 " + cat : "580 Hors banque", rs.getLong(5), "manuel"));
                return 0;
            }, companyId, from, to);
            out.sort(Comparator.comparingLong(JournalEntry::at).reversed());
            return out.size() > limit ? new ArrayList<>(out.subList(0, Math.max(1, limit))) : out;
        });
    }

    // ------------------------------------------------------------------ ecritures manuelles

    public CompletableFuture<Long> addManual(Actor actor, long companyId, boolean income, String category, String label, long amount) {
        return db.write(c -> {
            authorize(c, actor, companyId, true);
            String cat = Validation.text(category, 2, 24, "Catégorie");
            String l = Validation.text(label, 3, 80, "Libellé");
            if (amount <= 0 || amount > Validation.MAX_AMOUNT) throw new EconomyException("BAD_AMOUNT", "Montant invalide.");
            if (Sql.scalar(c, "SELECT COUNT(*) FROM manual_entries WHERE company_id = ? AND deleted_at IS NULL", companyId) >= MAX_MANUAL)
                throw new EconomyException("ENTRY_LIMIT", "Trop d'écritures manuelles.");
            long now = clock.getAsLong();
            long id = Sql.insert(c, "INSERT INTO manual_entries(company_id,kind,category,label,amount,entry_at,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    companyId, income ? "INCOME" : "EXPENSE", cat, l, amount, now, actor.id(), now);
            Records.audit(c, actor.id(), "ACCT_ENTRY", "manual_entry", id, (income ? "+" : "-") + amount + " " + cat + " " + l, now);
            return id;
        });
    }

    public CompletableFuture<Void> deleteManual(Actor actor, long entryId) {
        return db.write(c -> {
            Long companyId = Sql.one(c, "SELECT company_id FROM manual_entries WHERE id = ? AND deleted_at IS NULL", rs -> rs.getLong(1), entryId)
                    .orElseThrow(() -> new EconomyException("NOT_FOUND", "Écriture introuvable."));
            authorize(c, actor, companyId, true);
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE manual_entries SET deleted_at = ? WHERE id = ?", now, entryId); // jamais effacee : l'audit la garde
            Records.audit(c, actor.id(), "ACCT_ENTRY_DELETED", "manual_entry", entryId, "", now);
            return null;
        });
    }

    public CompletableFuture<List<Manual>> manualEntries(Actor actor, long companyId, int limit) {
        return db.read(c -> {
            authorize(c, actor, companyId, false);
            return Sql.query(c, "SELECT m.id, m.kind, m.category, m.label, m.amount, m.entry_at, COALESCE(p.name,'?') FROM manual_entries m LEFT JOIN players p ON p.uuid = m.created_by "
                    + "WHERE m.company_id = ? AND m.deleted_at IS NULL ORDER BY m.id DESC LIMIT ?",
                    rs -> new Manual(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getString(7)),
                    companyId, Math.max(1, Math.min(limit, 100)));
        });
    }
}
