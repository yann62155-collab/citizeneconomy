package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AnnouncementInvoiceTest {
    @TempDir Path tmp;
    Database db;
    AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    CompanyManager companies;
    EmployeeManager employees;
    BankManager bank;
    AnnouncementManager ann;
    InvoiceManager invoices;

    final Actor admin = new Actor(UUID.randomUUID(), "Admin", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);   // proprietaire
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);       // employe
    final Actor carl = new Actor(UUID.randomUUID(), "Carl", false);     // client

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        companies = new CompanyManager(db);
        employees = new EmployeeManager(db);
        bank = new BankManager(db);
        ann = new AnnouncementManager(db, clock::get);
        invoices = new InvoiceManager(db, clock::get);
    }

    @AfterEach
    void tearDown() { db.close(); }

    static String code(Supplier<?> s) {
        CompletionException e = assertThrows(CompletionException.class, s::get);
        return ((EconomyException) EconomyException.unwrap(e)).code();
    }

    long company() {
        long req = companies.submitRequest(alice, "Forge Royale", "", "Artisanat", "").join();
        long id = companies.accept(admin, req, "").join();
        employees.hire(alice, id, bob.id(), bob.name(), 0).join();
        db.write(c -> { Records.touchPlayer(c, carl.id(), carl.name(), 1); return null; }).join();
        return id;
    }

    @Test
    void announcementPermissionsAndModeration() {
        long id = company();
        long a1 = ann.publish(bob, id, "Recrutement forgeron", "Nous cherchons un forgeron", AnnouncementCategory.RECRUITMENT, AnnouncementVisibility.PUBLIC, null).join();
        assertEquals(1, ann.publicBoard(null, 10, 0).join().size());
        assertEquals("FORBIDDEN", code(() -> ann.publish(carl, id, "Faux", "Pas membre", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join()));
        // un employe ne modifie pas l'annonce d'un autre ; le proprietaire (gestion) le peut
        clock.addAndGet(60_000);
        long a2 = ann.publish(alice, id, "Reunion", "Reunion lundi soir", AnnouncementCategory.MEETING, AnnouncementVisibility.INTERNAL, null).join();
        assertEquals("FORBIDDEN", code(() -> ann.edit(bob, a2, "Reunion", "Modifie par bob", AnnouncementCategory.MEETING, AnnouncementVisibility.INTERNAL, null).join()));
        ann.edit(alice, a1, "Recrutement forgeron", "Mise a jour", AnnouncementCategory.RECRUITMENT, AnnouncementVisibility.PUBLIC, null).join();
        assertEquals(1, ann.publicBoard(null, 10, 0).join().size()); // l'interne n'est pas public
        // moderation admin + suspension des publications
        ann.adminRemove(admin, a1, "Contenu inapproprie").join();
        assertEquals(0, ann.publicBoard(null, 10, 0).join().size());
        ann.setPublicationsSuspended(admin, id, true, "Abus").join();
        clock.addAndGet(60_000);
        assertEquals("PUBLISH_SUSPENDED", code(() -> ann.publish(bob, id, "Encore", "Nouvelle annonce", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join()));
    }

    @Test
    void antiSpamAndLimits() {
        long id = company();
        ann.publish(bob, id, "Annonce 1", "Texte numero un", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join();
        assertEquals("COOLDOWN", code(() -> ann.publish(bob, id, "Annonce 2", "Texte numero deux", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join()));
        clock.addAndGet(31_000);
        assertEquals("DUPLICATE", code(() -> ann.publish(bob, id, "annonce 1", "TEXTE NUMERO UN", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join()));
        assertEquals("BAD_TEXT", code(() -> ann.publish(bob, id, "Titre \u00a7cRouge", "Texte valide", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join()));
        assertEquals("BAD_TEXT", code(() -> ann.publish(bob, id, "T".repeat(61), "Texte valide", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join()));
        ann.setLimits(admin, new AnnouncementManager.Limits(100, 2000, 5, 30, 50)).join(); // modifiable a chaud
        ann.publish(bob, id, "T".repeat(61), "Texte valide", AnnouncementCategory.INFO, AnnouncementVisibility.PUBLIC, null).join();
    }

    @Test
    void invoiceLifecycleAndPayment() {
        long id = company();
        bank.deposit(carl, UUID.randomUUID(), 1000).join();
        long inv = invoices.create(alice, id, AccountRef.personal(carl.id()), "Epee en fer", 300, null).join();
        assertEquals("FORBIDDEN", code(() -> invoices.pay(alice, inv).join()));          // seul le destinataire paie
        assertEquals("FORBIDDEN", code(() -> invoices.create(bob, id, AccountRef.personal(carl.id()), "Sans droit", 5, null).join())); // employe sans ISSUE_INVOICES
        invoices.pay(carl, inv).join();
        assertEquals(700, bank.personalBalance(carl).join());
        assertEquals(300, bank.businessBalance(alice, id).join());                         // fonds recus par l'entreprise
        assertEquals("BAD_STATE", code(() -> invoices.pay(carl, inv).join()));            // une seule fois
        assertEquals("PAID", invoices.myInvoices(carl, null, 10).join().get(0).status());
        assertEquals(java.util.List.of(), new TransactionManager(db).verifyBalances().join());
    }

    @Test
    void insufficientFundsKeepsInvoicePending_refuseAndOverdue() {
        long id = company();
        long inv = invoices.create(alice, id, AccountRef.personal(carl.id()), "Armure", 500, clock.get() + 1000).join();
        assertEquals("INSUFFICIENT_FUNDS", code(() -> invoices.pay(carl, inv).join()));
        assertEquals("PENDING", invoices.myInvoices(carl, null, 10).join().get(0).status());
        clock.addAndGet(5000);
        assertEquals(1, invoices.markOverdue().join());
        assertEquals("OVERDUE", invoices.myInvoices(carl, null, 10).join().get(0).status());
        invoices.refuse(carl, inv).join();
        assertEquals("BAD_STATE", code(() -> invoices.cancel(alice, inv).join()));
    }
}
