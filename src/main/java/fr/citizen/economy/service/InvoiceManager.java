package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.PermissionManager.Authority;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Factures entreprise -> joueur ou entreprise. Le paiement transfere les fonds dans la meme transaction SQL que le
 * changement de statut, avec un identifiant de transaction deterministe : une facture ne peut etre payee qu'une fois.
 */
public final class InvoiceManager {
    public static final int MAX_PENDING_PER_ISSUER = 50;
    public static final int MAX_PENDING_PER_PAIR = 10;

    private final Database db;
    private final LongSupplier clock;

    public InvoiceManager(Database db) { this(db, System::currentTimeMillis); }
    public InvoiceManager(Database db, LongSupplier clock) { this.db = db; this.clock = clock; }

    private static final String SELECT =
            "SELECT i.id, i.number, i.issuer_company_id, ic.name, i.recipient_type, i.recipient_id, "
          + "COALESCE(rp.name, rc.name, '?'), i.description, i.amount, i.issued_at, i.due_at, i.status, i.paid_tx_id FROM invoices i "
          + "JOIN companies ic ON ic.id = i.issuer_company_id "
          + "LEFT JOIN players rp ON i.recipient_type = 'PLAYER' AND rp.uuid = i.recipient_id "
          + "LEFT JOIN companies rc ON i.recipient_type = 'COMPANY' AND CAST(rc.id AS TEXT) = i.recipient_id ";

    private static final Sql.RowMapper<Invoice> MAPPER = rs -> new Invoice(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getLong(10),
            rs.getObject(11) == null ? null : rs.getLong(11), rs.getString(12), rs.getObject(13) == null ? null : rs.getLong(13));

    public static UUID paymentTxId(long invoiceId) {
        return UUID.nameUUIDFromBytes(("citizeneconomy:invoice:" + invoiceId).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ emission

    public CompletableFuture<Long> create(Actor actor, long issuerCompanyId, AccountRef recipient, String description, long amount, Long dueAt) {
        return db.write(c -> {
            Authority a = PermissionManager.authorize(c, actor, issuerCompanyId, Permission.ISSUE_INVOICES, true);
            if (a.member() == null) throw new EconomyException("FORBIDDEN", "Un administrateur n'émet pas de facture au nom d'une entreprise.");
            if (recipient == null || (recipient.type() != AccountType.PERSONAL && recipient.type() != AccountType.BUSINESS))
                throw new EconomyException("BAD_TX", "Destinataire invalide.");
            String d = Validation.text(description, 3, 200, "Description");
            if (amount <= 0 || amount > Validation.MAX_AMOUNT) throw new EconomyException("BAD_AMOUNT", "Montant invalide.");
            long now = clock.getAsLong();
            if (dueAt != null && (dueAt <= now || dueAt > now + 365L * 86_400_000L))
                throw new EconomyException("BAD_EXPIRY", "Date limite invalide (dans le futur, 1 an maximum).");

            String rType; String rId;
            if (recipient.type() == AccountType.PERSONAL) {
                if (!Sql.exists(c, "SELECT 1 FROM players WHERE uuid = ?", recipient.id())) throw new EconomyException("TARGET_UNKNOWN", "Joueur destinataire inconnu.");
                rType = "PLAYER"; rId = recipient.id();
            } else {
                long rc = Long.parseLong(recipient.id());
                if (rc == issuerCompanyId) throw new EconomyException("BAD_TX", "Une entreprise ne peut pas se facturer elle-même.");
                if (Records.company(c, rc).status() != CompanyStatus.ACTIVE) throw new EconomyException("COMPANY_NOT_ACTIVE", "L'entreprise destinataire n'est pas active.");
                rType = "COMPANY"; rId = recipient.id();
            }
            if (Sql.scalar(c, "SELECT COUNT(*) FROM invoices WHERE issuer_company_id = ? AND status IN ('PENDING','OVERDUE')", issuerCompanyId) >= MAX_PENDING_PER_ISSUER)
                throw new EconomyException("INVOICE_LIMIT", "Trop de factures en attente pour cette entreprise.");
            if (Sql.scalar(c, "SELECT COUNT(*) FROM invoices WHERE issuer_company_id = ? AND recipient_type = ? AND recipient_id = ? AND status IN ('PENDING','OVERDUE')",
                    issuerCompanyId, rType, rId) >= MAX_PENDING_PER_PAIR)
                throw new EconomyException("INVOICE_LIMIT", "Trop de factures en attente pour ce destinataire.");

            long id = Sql.insert(c, "INSERT INTO invoices(number,issuer_company_id,recipient_type,recipient_id,description,amount,issued_at,due_at,status,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?,?, 'PENDING', ?)", UUID.randomUUID().toString(), issuerCompanyId, rType, rId, d, amount, now, dueAt, now);
            Sql.update(c, "UPDATE invoices SET number = ? WHERE id = ?", String.format("FAC-%06d", id), id);
            Records.audit(c, actor.id(), "INVOICE_CREATED", "invoice", id, amount + " PO -> " + rType + ":" + rId, now);
            notifyRecipient(c, rType, rId, "INVOICE_RECEIVED", "Facture " + String.format("FAC-%06d", id) + " de « " + a.company().name() + " » : " + amount + " PO.", now);
            return id;
        });
    }

    // ------------------------------------------------------------------ reponse du destinataire

    public CompletableFuture<TxResult> pay(Actor actor, long invoiceId) {
        return db.write(c -> {
            Invoice inv = get(c, invoiceId);
            requirePayable(inv);
            AccountRef payer = payer(c, actor, inv, true);
            TxResult r = TransactionManager.apply(c, new TxRequest(paymentTxId(invoiceId), TxType.INVOICE_PAYMENT, inv.amount(), payer,
                    AccountRef.business(inv.issuerCompanyId()), "Facture " + inv.number(), actor.id(), invoiceId));
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE invoices SET status='PAID', paid_tx_id=?, updated_at=? WHERE id=? AND status IN ('PENDING','OVERDUE')", r.id(), now, invoiceId);
            Records.audit(c, actor.id(), "INVOICE_PAID", "invoice", invoiceId, inv.number(), now);
            notifyIssuer(c, inv.issuerCompanyId(), "INVOICE_PAID", "Facture " + inv.number() + " payée : " + inv.amount() + " PO.", now);
            return r;
        });
    }

    public CompletableFuture<Void> refuse(Actor actor, long invoiceId) {
        return db.write(c -> {
            Invoice inv = get(c, invoiceId);
            requirePayable(inv);
            payer(c, actor, inv, false); // verifie seulement que l'auteur represente le destinataire
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE invoices SET status='REFUSED', updated_at=? WHERE id=?", now, invoiceId);
            Records.audit(c, actor.id(), "INVOICE_REFUSED", "invoice", invoiceId, inv.number(), now);
            notifyIssuer(c, inv.issuerCompanyId(), "INVOICE_REFUSED", "Facture " + inv.number() + " refusée par " + inv.recipientName() + ".", now);
            return null;
        });
    }

    public CompletableFuture<Void> cancel(Actor actor, long invoiceId) {
        return db.write(c -> {
            Invoice inv = get(c, invoiceId);
            requirePayable(inv);
            PermissionManager.authorize(c, actor, inv.issuerCompanyId(), Permission.ISSUE_INVOICES, true);
            long now = clock.getAsLong();
            Sql.update(c, "UPDATE invoices SET status='CANCELLED', updated_at=? WHERE id=?", now, invoiceId);
            Records.audit(c, actor.id(), "INVOICE_CANCELLED", "invoice", invoiceId, inv.number(), now);
            notifyRecipient(c, inv.recipientType(), inv.recipientId(), "INVOICE_CANCELLED", "Facture " + inv.number() + " annulée par l'émetteur.", now);
            return null;
        });
    }

    /** Passe en retard les factures dont la date limite est depassee. A appeler regulierement. */
    public CompletableFuture<Integer> markOverdue() {
        return db.write(c -> {
            long now = clock.getAsLong();
            List<Invoice> late = Sql.query(c, SELECT + "WHERE i.status = 'PENDING' AND i.due_at IS NOT NULL AND i.due_at < ?", MAPPER, now);
            for (Invoice inv : late) {
                Sql.update(c, "UPDATE invoices SET status='OVERDUE', updated_at=? WHERE id=? AND status='PENDING'", now, inv.id());
                notifyRecipient(c, inv.recipientType(), inv.recipientId(), "INVOICE_OVERDUE", "Facture " + inv.number() + " en retard : " + inv.amount() + " PO.", now);
                notifyIssuer(c, inv.issuerCompanyId(), "INVOICE_OVERDUE", "Facture " + inv.number() + " en retard (" + inv.recipientName() + ").", now);
            }
            return late.size();
        });
    }

    // ------------------------------------------------------------------ lecture

    public CompletableFuture<List<Invoice>> myInvoices(Actor actor, String statusOrNull, int limit) {
        return db.read(c -> Sql.query(c, SELECT + "WHERE i.recipient_type = 'PLAYER' AND i.recipient_id = ? AND (? IS NULL OR i.status = ?) ORDER BY i.id DESC LIMIT ?",
                MAPPER, actor.id(), statusOrNull, statusOrNull, clamp(limit)));
    }

    public CompletableFuture<List<Invoice>> receivedByCompany(Actor actor, long companyId, String statusOrNull, int limit) {
        return db.read(c -> {
            PermissionManager.authorize(c, actor, companyId, Permission.VIEW_FINANCES, false);
            return Sql.query(c, SELECT + "WHERE i.recipient_type = 'COMPANY' AND i.recipient_id = ? AND (? IS NULL OR i.status = ?) ORDER BY i.id DESC LIMIT ?",
                    MAPPER, Long.toString(companyId), statusOrNull, statusOrNull, clamp(limit));
        });
    }

    public CompletableFuture<List<Invoice>> issuedByCompany(Actor actor, long companyId, String statusOrNull, int limit) {
        return db.read(c -> {
            Authority a = PermissionManager.authorize(c, actor, companyId, null, false);
            if (!a.admin() && !PermissionManager.has(a.member(), Permission.ISSUE_INVOICES) && !PermissionManager.has(a.member(), Permission.VIEW_FINANCES))
                throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
            return Sql.query(c, SELECT + "WHERE i.issuer_company_id = ? AND (? IS NULL OR i.status = ?) ORDER BY i.id DESC LIMIT ?",
                    MAPPER, companyId, statusOrNull, statusOrNull, clamp(limit));
        });
    }

    public CompletableFuture<List<Invoice>> adminList(Actor admin, String statusOrNull, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, SELECT + "WHERE (? IS NULL OR i.status = ?) ORDER BY i.id DESC LIMIT ? OFFSET ?",
                    MAPPER, statusOrNull, statusOrNull, clamp(limit), Math.max(0, offset));
        });
    }

    // ------------------------------------------------------------------ interne

    private static int clamp(int limit) { return Math.max(1, Math.min(limit, 100)); }

    private static Invoice get(Connection c, long id) throws SQLException {
        return Sql.one(c, SELECT + "WHERE i.id = ?", MAPPER, id).orElseThrow(() -> new EconomyException("NOT_FOUND", "Facture introuvable."));
    }

    private static void requirePayable(Invoice inv) {
        if (!inv.status().equals("PENDING") && !inv.status().equals("OVERDUE"))
            throw new EconomyException("BAD_STATE", "Cette facture n'est plus en attente.");
    }

    /** Compte debite pour cette facture, apres verification que l'auteur represente bien le destinataire. */
    private static AccountRef payer(Connection c, Actor actor, Invoice inv, boolean spending) throws SQLException {
        if (inv.recipientType().equals("PLAYER")) {
            if (!inv.recipientId().equals(actor.id().toString())) throw new EconomyException("FORBIDDEN", "Cette facture ne vous est pas adressée.");
            return AccountRef.personal(actor.id());
        }
        long companyId = Long.parseLong(inv.recipientId());
        PermissionManager.Authority au = PermissionManager.authorize(c, actor, companyId, Permission.MANAGE_FINANCES, spending);
        if (au.member() == null) throw new EconomyException("FORBIDDEN", "Un administrateur ne paie pas au nom d'une entreprise.");
        return AccountRef.business(companyId);
    }

    private static void notifyRecipient(Connection c, String type, String id, String notif, String msg, long now) throws SQLException {
        if (type.equals("PLAYER")) Records.notify(c, UUID.fromString(id), notif, msg, now);
        else {
            Company co = Records.company(c, Long.parseLong(id));
            Records.notify(c, co.owner(), notif, "« " + co.name() + " » : " + msg, now);
            if (co.manager() != null) Records.notify(c, co.manager(), notif, "« " + co.name() + " » : " + msg, now);
        }
    }

    private static void notifyIssuer(Connection c, long companyId, String notif, String msg, long now) throws SQLException {
        notifyRecipient(c, "COMPANY", Long.toString(companyId), notif, msg, now);
    }
}
