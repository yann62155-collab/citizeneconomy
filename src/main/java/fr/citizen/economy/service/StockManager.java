package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.PermissionManager.Authority;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Produits et stock d'une entreprise. Le cout unitaire suit la methode du cout moyen pondere : chaque reapprovisionnement
 * recalcule le cout moyen du produit. Chaque variation de stock laisse une ligne dans stock_movements (jamais modifiee).
 */
public final class StockManager {
    public static final int MAX_PRODUCTS = 500;
    public static final long MAX_QTY = 1_000_000L;
    public static final long MAX_PRICE = 1_000_000_000L;
    private static final Pattern SKU = Pattern.compile("^[A-Za-z0-9_\\-]{1,16}$");

    /** marginBp : marge en points de base sur le cout (2500 = 25 %), -1 = prix fixe saisi a la main. buyback : prix de rachat unitaire, 0 = non rachete. */
    public record Product(long id, long companyId, String sku, String name, long price, long cost, long marginBp, long buyback, long stock, long minStock,
                          boolean active, long createdAt, long updatedAt) {
        public boolean low() { return minStock > 0 && stock <= minStock; }
        public long value() { return stock * cost; }
    }

    public record Movement(long id, long productId, String productName, long delta, String reason, long unitCost, String note,
                           String actor, long createdAt) { }

    private final Database db;
    private final LongSupplier clock;

    public StockManager(Database db) { this(db, System::currentTimeMillis); }
    public StockManager(Database db, LongSupplier clock) { this.db = db; this.clock = clock; }

    static final String SELECT = "SELECT id, company_id, sku, name, price, cost, COALESCE(margin_bp,-1), buyback, stock, min_stock, active, created_at, updated_at FROM products ";
    static final Sql.RowMapper<Product> MAPPER = rs -> new Product(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
            rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getInt(11) == 1, rs.getLong(12), rs.getLong(13));

    // ------------------------------------------------------------------ droits

    /** Lecture : caisse, stock ou comptabilite. */
    static Authority authorizeRead(Connection c, Actor actor, long companyId) throws SQLException {
        Authority a = PermissionManager.authorize(c, actor, companyId, null, false);
        if (!a.admin() && !PermissionManager.has(a.member(), Permission.CASHIER) && !PermissionManager.has(a.member(), Permission.MANAGE_STOCK)
                && !PermissionManager.has(a.member(), Permission.VIEW_ACCOUNTING))
            throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
        return a;
    }

    // ------------------------------------------------------------------ lecture

    public CompletableFuture<List<Product>> list(Actor actor, long companyId, boolean includeInactive) {
        return db.read(c -> {
            authorizeRead(c, actor, companyId);
            return Sql.query(c, SELECT + "WHERE company_id = ? AND (? = 1 OR active = 1) ORDER BY active DESC, name COLLATE NOCASE LIMIT ?",
                    MAPPER, companyId, includeInactive, MAX_PRODUCTS);
        });
    }

    public CompletableFuture<Product> get(Actor actor, long productId) {
        return db.read(c -> {
            Product p = product(c, productId);
            authorizeRead(c, actor, p.companyId());
            return p;
        });
    }

    public CompletableFuture<List<Movement>> movements(Actor actor, long productId, int limit) {
        return db.read(c -> {
            Product p = product(c, productId);
            authorizeRead(c, actor, p.companyId());
            return Sql.query(c, "SELECT m.id, m.product_id, p.name, m.delta, m.reason, m.unit_cost, m.note, COALESCE(pl.name,''), m.created_at "
                    + "FROM stock_movements m JOIN products p ON p.id = m.product_id LEFT JOIN players pl ON pl.uuid = m.actor_uuid "
                    + "WHERE m.product_id = ? ORDER BY m.id DESC LIMIT ?",
                    rs -> new Movement(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6),
                            rs.getString(7), rs.getString(8), rs.getLong(9)),
                    productId, Math.max(1, Math.min(limit, 100)));
        });
    }

    // ------------------------------------------------------------------ ecriture

    public CompletableFuture<Long> create(Actor actor, long companyId, String sku, String name, long price, long cost, long qty, long minStock) {
        return create(actor, companyId, sku, name, price, cost, qty, minStock, -1, 0);
    }

    /** Prix prevu pour un cout et une marge (arrondi a l'unite superieure : on ne vend jamais sous la marge voulue). */
    public static long priceFor(long cost, long marginBp) {
        return (cost * (10_000L + marginBp) + 9_999L) / 10_000L;
    }

    /**
     * @param marginBp marge sur le cout en points de base (2500 = 25 %) : le prix de vente est alors calcule et suit le cout a chaque
     *                 reapprovisionnement ; -1 = prix fixe
     * @param buyback prix de rachat unitaire aux joueurs (0 = l'entreprise ne rachete pas cet article)
     */
    public CompletableFuture<Long> create(Actor actor, long companyId, String sku, String name, long price, long cost, long qty, long minStock, long marginBp, long buyback) {
        return db.write(c -> {
            PermissionManager.authorize(c, actor, companyId, Permission.MANAGE_STOCK, true);
            String n = Validation.text(name, 2, 40, "Nom du produit");
            String code = sku == null ? "" : sku.trim();
            if (!code.isEmpty() && !SKU.matcher(code).matches())
                throw new EconomyException("BAD_SKU", "Référence : 1 à 16 lettres, chiffres, - ou _.");
            checkMargin(marginBp); checkMoney(buyback, "Prix de rachat");
            long sellPrice = marginBp >= 0 ? priceFor(cost, marginBp) : price;
            checkMoney(sellPrice, "Prix"); checkMoney(cost, "Coût");
            checkQty(qty, true); checkQty(minStock, true);
            if (Sql.scalar(c, "SELECT COUNT(*) FROM products WHERE company_id = ?", companyId) >= MAX_PRODUCTS)
                throw new EconomyException("PRODUCT_LIMIT", "Trop de produits (" + MAX_PRODUCTS + " maximum).");
            long now = clock.getAsLong();
            long id;
            try {
                id = Sql.insert(c, "INSERT INTO products(company_id,sku,name,price,cost,margin_bp,buyback,stock,min_stock,active,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,1,?,?)",
                        companyId, code.isEmpty() ? "TMP" + now + "_" + System.nanoTime() : code, n, sellPrice, cost, marginBp >= 0 ? marginBp : null, buyback, qty, minStock, now, now);
            } catch (SQLException e) {
                if (Sql.isUnique(e, "sku")) throw new EconomyException("SKU_TAKEN", "Cette référence existe déjà.");
                throw e;
            }
            if (code.isEmpty()) Sql.update(c, "UPDATE products SET sku = ? WHERE id = ?", String.format("P%04d", id), id);
            if (qty > 0) movement(c, companyId, id, qty, "RESTOCK", cost, "Stock initial", null, null, actor, now);
            Records.audit(c, actor.id(), "PRODUCT_CREATED", "product", id, n + " " + sellPrice + " PO", now);
            return id;
        });
    }

    /** Modifie la fiche : nom, prix de vente, seuil d'alerte, activation. Le cout ne change que par reapprovisionnement. */
    public CompletableFuture<Void> update(Actor actor, long productId, String name, long price, long minStock, boolean active) {
        return db.read(c -> product(c, productId)).thenCompose(p -> update(actor, productId, name, price, minStock, active, p.marginBp(), p.buyback()));
    }

    /** Fiche complete. marginBp &gt;= 0 : le prix est calcule depuis le cout actuel (le prix saisi est ignore) ; -1 : prix fixe. */
    public CompletableFuture<Void> update(Actor actor, long productId, String name, long price, long minStock, boolean active, long marginBp, long buyback) {
        return db.write(c -> {
            Product p = product(c, productId);
            PermissionManager.authorize(c, actor, p.companyId(), Permission.MANAGE_STOCK, true);
            String n = Validation.text(name, 2, 40, "Nom du produit");
            checkMargin(marginBp); checkMoney(buyback, "Prix de rachat");
            long sellPrice = marginBp >= 0 ? priceFor(p.cost(), marginBp) : price;
            checkMoney(sellPrice, "Prix"); checkQty(minStock, true);
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE products SET name=?, price=?, margin_bp=?, buyback=?, min_stock=?, active=?, updated_at=? WHERE id=?",
                    n, sellPrice, marginBp >= 0 ? marginBp : null, buyback, minStock, active, now, productId);
            Records.audit(c, actor.id(), "PRODUCT_UPDATED", "product", productId, n + " " + sellPrice + " PO, rachat " + buyback + " PO" + (active ? "" : " (désactivé)"), now);
            return null;
        });
    }

    /** Entree de marchandise. unitCost &lt; 0 = garder le cout actuel ; sinon nouveau cout moyen pondere. */
    public CompletableFuture<Void> restock(Actor actor, long productId, long qty, long unitCost, String note) {
        return db.write(c -> {
            Product p = product(c, productId);
            PermissionManager.authorize(c, actor, p.companyId(), Permission.MANAGE_STOCK, true);
            if (qty <= 0) throw new EconomyException("BAD_QTY", "Quantité invalide.");
            checkQty(qty, false);
            if (p.stock() + qty > MAX_QTY) throw new EconomyException("BAD_QTY", "Stock maximum dépassé (" + MAX_QTY + ").");
            long cost = unitCost < 0 ? p.cost() : unitCost;
            checkMoney(cost, "Coût");
            long newCost = (p.stock() + qty) == 0 ? cost : Math.round((double) (p.stock() * p.cost() + qty * cost) / (p.stock() + qty));
            long now = clock.getAsLong();
            long newPrice = p.marginBp() >= 0 ? priceFor(newCost, p.marginBp()) : p.price();
            Sql.update(c, "UPDATE products SET stock = stock + ?, cost = ?, price = ?, updated_at = ? WHERE id = ?", qty, newCost, newPrice, now, productId);
            movement(c, p.companyId(), productId, qty, "RESTOCK", cost, Validation.optional(note, 80, "Note"), null, null, actor, now);
            Records.audit(c, actor.id(), "STOCK_RESTOCK", "product", productId, qty + " × " + p.name() + " à " + cost + " PO", now);
            return null;
        });
    }

    /** Inventaire : fixe la quantite reelle. L'ecart (positif ou negatif) est enregistre. */
    public CompletableFuture<Void> adjust(Actor actor, long productId, long newQty, String note) {
        return db.write(c -> {
            Product p = product(c, productId);
            PermissionManager.authorize(c, actor, p.companyId(), Permission.MANAGE_STOCK, true);
            checkQty(newQty, true);
            long delta = newQty - p.stock();
            if (delta == 0) return null;
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE products SET stock = ?, updated_at = ? WHERE id = ?", newQty, now, productId);
            movement(c, p.companyId(), productId, delta, "ADJUST", p.cost(), Validation.optional(note, 80, "Note"), null, null, actor, now);
            Records.audit(c, actor.id(), "STOCK_ADJUST", "product", productId, p.stock() + " -> " + newQty + " " + p.name(), now);
            return null;
        });
    }

    /** Perte, casse, vol, peremption : sortie de stock sans vente. */
    public CompletableFuture<Void> loss(Actor actor, long productId, long qty, String note) {
        return db.write(c -> {
            Product p = product(c, productId);
            PermissionManager.authorize(c, actor, p.companyId(), Permission.MANAGE_STOCK, true);
            if (qty <= 0 || qty > p.stock()) throw new EconomyException("BAD_QTY", "Quantité invalide (stock actuel : " + p.stock() + ").");
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE products SET stock = stock - ?, updated_at = ? WHERE id = ?", qty, now, productId);
            movement(c, p.companyId(), productId, -qty, "LOSS", p.cost(), Validation.optional(note, 80, "Note"), null, null, actor, now);
            Records.audit(c, actor.id(), "STOCK_LOSS", "product", productId, qty + " × " + p.name(), now);
            return null;
        });
    }

    // ------------------------------------------------------------------ interne (partage avec les ventes)

    static Product product(Connection c, long id) throws SQLException {
        return Sql.one(c, SELECT + "WHERE id = ?", MAPPER, id).orElseThrow(() -> new EconomyException("NOT_FOUND", "Produit introuvable."));
    }

    static void movement(Connection c, long companyId, long productId, long delta, String reason, long unitCost, String note,
                         String refType, Long refId, Actor actor, long now) throws SQLException {
        Sql.update(c, "INSERT INTO stock_movements(company_id,product_id,delta,reason,unit_cost,note,ref_type,ref_id,actor_uuid,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                companyId, productId, delta, reason, unitCost, note == null ? "" : note, refType, refId, actor == null ? null : actor.id(), now);
    }

    private static void checkMoney(long v, String field) {
        if (v < 0 || v > MAX_PRICE) throw new EconomyException("BAD_AMOUNT", field + " invalide (0 à " + MAX_PRICE + ").");
    }

    private static void checkMargin(long bp) {
        if (bp < -1 || bp > 1_000_000L) throw new EconomyException("BAD_MARGIN", "Marge invalide (0 à 10 000 %).");
    }

    /** Entree de stock deja controlee (rachat) : recalcule le cout moyen et le prix si l'article suit une marge. */
    static void receive(Connection c, Product p, long qty, long unitCost, String note, String refType, Long refId, Actor actor, long now) throws SQLException {
        long newCost = Math.round((double) (p.stock() * p.cost() + qty * unitCost) / (p.stock() + qty));
        long newPrice = p.marginBp() >= 0 ? priceFor(newCost, p.marginBp()) : p.price();
        Sql.update(c, "UPDATE products SET stock = stock + ?, cost = ?, price = ?, updated_at = ? WHERE id = ?", qty, newCost, newPrice, now, p.id());
        movement(c, p.companyId(), p.id(), qty, "RESTOCK", unitCost, note, refType, refId, actor, now);
    }

    private static void checkQty(long v, boolean allowZero) {
        if (v < (allowZero ? 0 : 1) || v > MAX_QTY) throw new EconomyException("BAD_QTY", "Quantité invalide (0 à " + MAX_QTY + ").");
    }
}
