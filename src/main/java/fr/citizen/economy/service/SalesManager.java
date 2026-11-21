package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.PermissionManager.Authority;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Caisse. Une vente retire le stock immediatement (le client ne peut pas acheter ce qui n'existe plus) :
 * - sans client : vente au comptoir, encaissee tout de suite (especes) ;
 * - avec un client (joueur ou entreprise) : une facture est emise ; la vente est « payee » quand la facture est payee,
 *   et le stock est rendu si la facture est refusee ou annulee. Meme transaction SQL que la facture : jamais d'etat a moitie fait.
 */
public final class SalesManager {
    public static final int MAX_LINES = 30;
    public static final long MAX_QTY_PER_LINE = 10_000L;
    public static final long INVOICE_DELAY_MS = 7L * 86_400_000L;

    public record Item(long productId, long qty) { }

    public record Sale(long id, String number, long companyId, String cashier, String customer, String payment, String status,
                       long total, long costTotal, Long invoiceId, long createdAt, Long paidAt) {
        public long margin() { return total - costTotal; }
    }

    public record Line(long productId, String name, long qty, long unitPrice, long unitCost) {
        public long total() { return qty * unitPrice; }
    }

    private final Database db;
    private final InvoiceManager invoices;
    private final LongSupplier clock;

    public SalesManager(Database db, InvoiceManager invoices) { this(db, invoices, System::currentTimeMillis); }
    public SalesManager(Database db, InvoiceManager invoices, LongSupplier clock) { this.db = db; this.invoices = invoices; this.clock = clock; }

    private static final String SELECT = "SELECT s.id, s.number, s.company_id, COALESCE(p.name,'?'), s.customer_label, s.payment, s.status, s.total, s.cost_total, "
            + "s.invoice_id, s.created_at, s.paid_at FROM sales s LEFT JOIN players p ON p.uuid = s.cashier_uuid ";
    private static final Sql.RowMapper<Sale> MAPPER = rs -> new Sale(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getObject(10) == null ? null : rs.getLong(10), rs.getLong(11),
            rs.getObject(12) == null ? null : rs.getLong(12));

    // ------------------------------------------------------------------ encaissement

    /**
     * @param customer null = vente au comptoir (especes) ; sinon joueur ou entreprise a facturer
     * @param customerLabel nom a afficher pour le client
     */
    public CompletableFuture<Long> checkout(Actor actor, long companyId, List<Item> items, AccountRef customer, String customerLabel) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, Permission.CASHIER, true);
            if (a.member() == null) throw new EconomyException("FORBIDDEN", "Un administrateur ne tient pas la caisse d'une entreprise.");
            if (items == null || items.isEmpty()) throw new EconomyException("EMPTY_CART", "Le panier est vide.");
            Map<Long, Long> merged = new LinkedHashMap<>();
            for (Item i : items) {
                if (i.qty() <= 0 || i.qty() > MAX_QTY_PER_LINE) throw new EconomyException("BAD_QTY", "Quantité invalide.");
                merged.merge(i.productId(), i.qty(), Long::sum);
            }
            if (merged.size() > MAX_LINES) throw new EconomyException("CART_LIMIT", "Trop de lignes dans une vente (" + MAX_LINES + " maximum).");

            long now = clock.getAsLong();
            long total = 0, costTotal = 0;
            List<StockManager.Product> products = new ArrayList<>();
            StringBuilder desc = new StringBuilder();
            for (Map.Entry<Long, Long> e : merged.entrySet()) {
                StockManager.Product p = StockManager.product(c, e.getKey());
                if (p.companyId() != companyId) throw new EconomyException("NOT_FOUND", "Produit introuvable.");
                if (!p.active()) throw new EconomyException("PRODUCT_OFF", "« " + p.name() + " » n'est plus en vente.");
                if (p.stock() < e.getValue())
                    throw new EconomyException("OUT_OF_STOCK", "Stock insuffisant pour « " + p.name() + " » : " + p.stock() + " disponible(s).");
                total += e.getValue() * p.price();
                costTotal += e.getValue() * p.cost();
                products.add(p);
                if (desc.length() > 0) desc.append(", ");
                desc.append(e.getValue()).append("× ").append(p.name());
            }
            if (total <= 0 || total > Validation.MAX_AMOUNT) throw new EconomyException("BAD_AMOUNT", "Total de vente invalide.");

            boolean cash = customer == null;
            String label = cash ? "Comptoir" : (customerLabel == null || customerLabel.isBlank() ? "Client" : customerLabel.trim());
            String cType = cash ? "NONE" : customer.type() == AccountType.PERSONAL ? "PLAYER" : "COMPANY";
            long id = Sql.insert(c, "INSERT INTO sales(number,company_id,cashier_uuid,customer_type,customer_id,customer_label,payment,status,total,cost_total,created_at,paid_at) "
                    + "VALUES ('',?,?,?,?,?,?,?,?,?,?,?)", companyId, actor.id(), cType, cash ? null : customer.id(), clip(label, 60),
                    cash ? "CASH" : "INVOICE", cash ? "PAID" : "PENDING", total, costTotal, now, cash ? now : null);
            String number = String.format("V-%06d", id);
            Sql.update(c, "UPDATE sales SET number = ? WHERE id = ?", number, id);

            for (StockManager.Product p : products) {
                long qty = merged.get(p.id());
                // garde SQL : le stock ne peut jamais devenir negatif, meme si deux caisses vendent en meme temps
                if (Sql.update(c, "UPDATE products SET stock = stock - ?, updated_at = ? WHERE id = ? AND stock >= ?", qty, now, p.id(), qty) != 1)
                    throw new EconomyException("OUT_OF_STOCK", "Stock insuffisant pour « " + p.name() + " ».");
                Sql.update(c, "INSERT INTO sale_lines(sale_id,product_id,name,qty,unit_price,unit_cost) VALUES (?,?,?,?,?,?)", id, p.id(), p.name(), qty, p.price(), p.cost());
                StockManager.movement(c, companyId, p.id(), -qty, "SALE", p.cost(), number, "sale", id, actor, now);
            }

            if (!cash) {
                String d = "Vente " + number + " : " + desc;
                long invoiceId = invoices.createCore(c, actor, companyId, customer, clip(d, 200), total, now + INVOICE_DELAY_MS, a);
                Sql.update(c, "UPDATE sales SET invoice_id = ? WHERE id = ?", invoiceId, id);
            }
            Records.audit(c, actor.id(), "SALE_CREATED", "sale", id, number + " " + total + " PO " + (cash ? "comptoir" : "facture"), now);
            return id;
        });
    }

    /**
     * Annule une vente et remet les articles en stock.
     * Vente en attente de paiement : la facture est annulee. Vente au comptoir : il faut le droit de gerer les finances (remboursement).
     * Une vente deja payee par facture ne s'annule pas : le remboursement est un virement a part.
     */
    public CompletableFuture<Void> cancel(Actor actor, long saleId) {
        return db.write(c -> {
            Sale s = get(c, saleId);
            if (s.status().equals("CANCELLED")) throw new EconomyException("BAD_STATE", "Cette vente est déjà annulée.");
            long now = clock.getAsLong();
            if (s.payment().equals("INVOICE")) {
                if (!s.status().equals("PENDING"))
                    throw new EconomyException("BAD_STATE", "Cette vente est déjà payée : remboursez le client par un virement.");
                PermissionManager.authorize(c, actor, s.companyId(), Permission.CASHIER, true);
                invoices.cancelCore(c, actor, s.invoiceId());
                // le crochet onInvoiceClosed de InvoiceManager n'est pas appele par cancelCore : on ferme la vente ici
                closeAndRestock(c, s, now, actor.id());
            } else {
                PermissionManager.authorize(c, actor, s.companyId(), Permission.MANAGE_FINANCES, true);
                closeAndRestock(c, s, now, actor.id());
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ crochets appeles par InvoiceManager (meme transaction)

    static void onInvoicePaid(Connection c, long invoiceId, long now) throws SQLException {
        Sql.update(c, "UPDATE sales SET status = 'PAID', paid_at = ? WHERE invoice_id = ? AND status = 'PENDING'", now, invoiceId);
    }

    static void onInvoiceClosed(Connection c, long invoiceId, long now, UUID actor) throws SQLException {
        Optional<Sale> s = Sql.one(c, SELECT + "WHERE s.invoice_id = ? AND s.status = 'PENDING'", MAPPER, invoiceId);
        if (s.isPresent()) closeAndRestock(c, s.get(), now, actor);
    }

    private static void closeAndRestock(Connection c, Sale s, long now, UUID actor) throws SQLException {
        if (Sql.update(c, "UPDATE sales SET status = 'CANCELLED', closed_at = ? WHERE id = ? AND status <> 'CANCELLED'", now, s.id()) != 1) return;
        List<Line> lines = Sql.query(c, "SELECT product_id, name, qty, unit_price, unit_cost FROM sale_lines WHERE sale_id = ?",
                rs -> new Line(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)), s.id());
        for (Line l : lines) {
            Sql.update(c, "UPDATE products SET stock = stock + ?, updated_at = ? WHERE id = ?", l.qty(), now, l.productId());
            Sql.update(c, "INSERT INTO stock_movements(company_id,product_id,delta,reason,unit_cost,note,ref_type,ref_id,actor_uuid,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    s.companyId(), l.productId(), l.qty(), "RETURN", l.unitCost(), s.number(), "sale", s.id(), actor, now);
        }
        Records.audit(c, actor, "SALE_CANCELLED", "sale", s.id(), s.number() + " " + s.total() + " PO", now);
    }

    // ------------------------------------------------------------------ lecture

    public CompletableFuture<List<Sale>> list(Actor actor, long companyId, String statusOrNull, int limit, int offset) {
        return db.read(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, null, false);
            if (!a.admin() && !PermissionManager.has(a.member(), Permission.CASHIER) && !PermissionManager.has(a.member(), Permission.VIEW_ACCOUNTING))
                throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
            return Sql.query(c, SELECT + "WHERE s.company_id = ? AND (? IS NULL OR s.status = ?) ORDER BY s.id DESC LIMIT ? OFFSET ?",
                    MAPPER, companyId, statusOrNull, statusOrNull, Math.max(1, Math.min(limit, 100)), Math.max(0, offset));
        });
    }

    public CompletableFuture<List<Line>> lines(Actor actor, long saleId) {
        return db.read(c -> {
            Sale s = get(c, saleId);
            Authority a = PermissionManager.authorize(c, actor, s.companyId(), null, false);
            if (!a.admin() && !PermissionManager.has(a.member(), Permission.CASHIER) && !PermissionManager.has(a.member(), Permission.VIEW_ACCOUNTING))
                throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
            return Sql.query(c, "SELECT product_id, name, qty, unit_price, unit_cost FROM sale_lines WHERE sale_id = ? ORDER BY id",
                    rs -> new Line(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)), saleId);
        });
    }

    private static Sale get(Connection c, long id) throws SQLException {
        return Sql.one(c, SELECT + "WHERE s.id = ?", MAPPER, id).orElseThrow(() -> new EconomyException("NOT_FOUND", "Vente introuvable."));
    }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
