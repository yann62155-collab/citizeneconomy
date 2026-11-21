package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Rachat de marchandise (minerais...) a un joueur. Le patron fixe le prix de rachat de chaque article ; la personne
 * chargee du stock enregistre l'apport : la banque de l'entreprise paie le joueur (virement), le stock entre au prix de rachat
 * (le cout moyen et, si l'article suit une marge, le prix de vente sont recalcules) et une facture de rachat est conservee.
 * Tout se fait dans UNE transaction SQL : jamais d'argent parti sans stock entre, ni l'inverse.
 */
public final class BuybackManager {
    public static final long MAX_QTY = 10_000L;

    public record Buyback(long id, String number, long companyId, long productId, String productName, String seller, long qty,
                          long unitPrice, long total, String by, long createdAt) { }

    private final Database db;
    private final LongSupplier clock;

    public BuybackManager(Database db) { this(db, System::currentTimeMillis); }
    public BuybackManager(Database db, LongSupplier clock) { this.db = db; this.clock = clock; }

    public static UUID txId(long buybackId) {
        return UUID.nameUUIDFromBytes(("citizeneconomy:buyback:" + buybackId).getBytes(StandardCharsets.UTF_8));
    }

    public CompletableFuture<Long> buy(Actor actor, long productId, UUID seller, long qty) {
        return db.write(c -> {
            StockManager.Product p = StockManager.product(c, productId);
            PermissionManager.Authority a = PermissionManager.authorize(c, actor, p.companyId(), Permission.MANAGE_STOCK, true);
            if (a.member() == null) throw new EconomyException("FORBIDDEN", "Un administrateur ne rachète pas de marchandise au nom d'une entreprise.");
            if (!p.active()) throw new EconomyException("PRODUCT_OFF", "« " + p.name() + " » n'est plus géré.");
            if (p.buyback() <= 0) throw new EconomyException("NO_BUYBACK", "« " + p.name() + " » n'a pas de prix de rachat : le patron doit le fixer.");
            if (qty <= 0 || qty > MAX_QTY) throw new EconomyException("BAD_QTY", "Quantité invalide (1 à " + MAX_QTY + ").");
            if (p.stock() + qty > StockManager.MAX_QTY) throw new EconomyException("BAD_QTY", "Stock maximum dépassé.");
            if (seller == null || !Sql.exists(c, "SELECT 1 FROM players WHERE uuid = ?", seller)) throw new EconomyException("TARGET_UNKNOWN", "Vendeur inconnu.");
            // un employe ne se rachete pas de marchandise a lui-meme (il se paierait avec l'argent de l'entreprise)
            if (seller.equals(actor.id()) && a.member().role() != CompanyRole.OWNER)
                throw new EconomyException("FORBIDDEN", "Vous ne pouvez pas vous racheter de marchandise à vous-même.");
            long total = qty * p.buyback();
            long now = clock.getAsLong();
            long id = Sql.insert(c, "INSERT INTO buybacks(number,company_id,product_id,product_name,seller_uuid,qty,unit_price,total,created_by,created_at) VALUES ('',?,?,?,?,?,?,?,?,?)",
                    p.companyId(), productId, p.name(), seller, qty, p.buyback(), total, actor.id(), now);
            String number = String.format("R-%06d", id);
            Sql.update(c, "UPDATE buybacks SET number = ? WHERE id = ?", number, id);
            // le virement echoue (EconomyException INSUFFICIENT_FUNDS) => toute la transaction est annulee, rien n'est enregistre
            TransactionManager.apply(c, new TxRequest(txId(id), TxType.TRANSFER, total, AccountRef.business(p.companyId()), AccountRef.personal(seller),
                    "Rachat " + number + " : " + qty + " × " + p.name(), actor.id()));
            StockManager.receive(c, p, qty, p.buyback(), number, "buyback", id, actor, now);
            Records.audit(c, actor.id(), "BUYBACK", "buyback", id, number + " " + qty + " × " + p.name() + " = " + total + " PO", now);
            Records.notify(c, seller, "BUYBACK", "« " + a.company().name() + " » vous a racheté " + qty + " × " + p.name() + " : " + total + " PO (facture " + number + ").", now);
            return id;
        });
    }

    public CompletableFuture<List<Buyback>> list(Actor actor, long companyId, int limit) {
        return db.read(c -> {
            StockManager.authorizeRead(c, actor, companyId);
            return Sql.query(c, "SELECT b.id, b.number, b.company_id, b.product_id, b.product_name, COALESCE(s.name,'?'), b.qty, b.unit_price, b.total, COALESCE(u.name,'?'), b.created_at "
                    + "FROM buybacks b LEFT JOIN players s ON s.uuid = b.seller_uuid LEFT JOIN players u ON u.uuid = b.created_by WHERE b.company_id = ? ORDER BY b.id DESC LIMIT ?",
                    rs -> new Buyback(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getLong(7),
                            rs.getLong(8), rs.getLong(9), rs.getString(10), rs.getLong(11)),
                    companyId, Math.max(1, Math.min(limit, 100)));
        });
    }
}
