package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class StockSalesAccountingTest {
    @TempDir Path tmp;
    Database db;
    AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    CompanyManager companies;
    EmployeeManager employees;
    BankManager bank;
    InvoiceManager invoices;
    StockManager stock;
    SalesManager sales;
    AccountingManager acct;
    BuybackManager buybacks;

    final Actor admin = new Actor(UUID.randomUUID(), "Admin", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);   // patronne
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);       // employe
    final Actor carl = new Actor(UUID.randomUUID(), "Carl", false);     // client
    final Actor dave = new Actor(UUID.randomUUID(), "Dave", false);     // inconnu de l'entreprise

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        companies = new CompanyManager(db);
        employees = new EmployeeManager(db);
        bank = new BankManager(db);
        invoices = new InvoiceManager(db, clock::get);
        stock = new StockManager(db, clock::get);
        sales = new SalesManager(db, invoices, clock::get);
        acct = new AccountingManager(db, clock::get);
        buybacks = new BuybackManager(db, clock::get);
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
        db.write(c -> { Records.touchPlayer(c, carl.id(), carl.name(), 1); Records.touchPlayer(c, dave.id(), dave.name(), 1); return null; }).join();
        return id;
    }

    long product(long cid, String name, long price, long cost, long qty) {
        return stock.create(alice, cid, "", name, price, cost, qty, 2).join();
    }

    @Test
    void stockRightsCostAveragingAndHistory() {
        long cid = company();
        // l'employe n'a aucun droit de stock tant que le patron ne les donne pas
        assertEquals("FORBIDDEN", code(() -> stock.create(bob, cid, "", "Epee", 100, 40, 5, 1).join()));
        assertEquals("FORBIDDEN", code(() -> stock.list(bob, cid, false).join()));
        employees.setPermissions(alice, cid, bob.id(), Permission.MANAGE_STOCK.bit()).join();
        assertEquals(0, stock.list(bob, cid, false).join().size());
        long p = stock.create(bob, cid, "EPE", "Epee", 100, 40, 10, 3).join();
        assertEquals("SKU_TAKEN", code(() -> stock.create(alice, cid, "epe", "Autre", 1, 1, 1, 0).join()));
        assertEquals("BAD_AMOUNT", code(() -> stock.create(alice, cid, "", "Negatif", -1, 0, 0, 0).join()));
        // cout moyen pondere : 10 a 40 puis 10 a 60 = 50
        stock.restock(alice, p, 10, 60, "Fournisseur").join();
        StockManager.Product x = stock.get(alice, p).join();
        assertEquals(20, x.stock());
        assertEquals(50, x.cost());
        // perte et inventaire laissent une trace
        stock.loss(alice, p, 2, "Casse").join();
        assertEquals("BAD_QTY", code(() -> stock.loss(alice, p, 99, "").join()));
        stock.adjust(alice, p, 15, "Inventaire").join();
        assertEquals(15, stock.get(alice, p).join().stock());
        List<StockManager.Movement> mv = stock.movements(alice, p, 20).join();
        assertEquals(List.of("ADJUST", "LOSS", "RESTOCK", "RESTOCK"), mv.stream().map(StockManager.Movement::reason).toList());
        // un inconnu ne voit rien
        assertEquals("FORBIDDEN", code(() -> stock.list(dave, cid, false).join()));
    }

    @Test
    void counterSaleReducesStockAndNeverGoesNegative() {
        long cid = company();
        long pain = product(cid, "Pain", 5, 2, 10);
        long vin = product(cid, "Vin", 20, 8, 3);
        assertEquals("FORBIDDEN", code(() -> sales.checkout(bob, cid, List.of(new SalesManager.Item(pain, 1)), null, null).join())); // pas de droit de caisse
        employees.setPermissions(alice, cid, bob.id(), Permission.CASHIER.bit()).join();
        assertEquals("EMPTY_CART", code(() -> sales.checkout(bob, cid, List.of(), null, null).join()));
        assertEquals("OUT_OF_STOCK", code(() -> sales.checkout(bob, cid, List.of(new SalesManager.Item(vin, 4)), null, null).join()));
        assertEquals(3, stock.get(alice, vin).join().stock()); // refus : rien n'a bouge
        long sale = sales.checkout(bob, cid, List.of(new SalesManager.Item(pain, 4), new SalesManager.Item(vin, 2), new SalesManager.Item(pain, 1)), null, null).join();
        assertEquals(5, stock.get(alice, pain).join().stock());   // 4 + 1 fusionnes
        assertEquals(1, stock.get(alice, vin).join().stock());
        SalesManager.Sale s = sales.list(bob, cid, null, 10, 0).join().get(0);
        assertEquals(sale, s.id());
        assertEquals("PAID", s.status());
        assertEquals(5 * 5 + 2 * 20, s.total());           // 65
        assertEquals(5 * 2 + 2 * 8, s.costTotal());        // 26
        assertEquals(2, sales.lines(bob, sale).join().size());
        // le caissier ne rembourse pas tout seul une vente au comptoir
        assertEquals("FORBIDDEN", code(() -> sales.cancel(bob, sale).join()));
        sales.cancel(alice, sale).join();
        assertEquals(10, stock.get(alice, pain).join().stock());
        assertEquals(3, stock.get(alice, vin).join().stock());
        assertEquals("BAD_STATE", code(() -> sales.cancel(alice, sale).join()));
    }

    @Test
    void invoiceSaleIsPaidByTheCustomerOrGivesTheStockBack() {
        long cid = company();
        long epee = product(cid, "Epee", 300, 120, 5);
        bank.deposit(carl, UUID.randomUUID(), 1000).join();
        long sale = sales.checkout(alice, cid, List.of(new SalesManager.Item(epee, 2)), AccountRef.personal(carl.id()), "Carl").join();
        assertEquals(3, stock.get(alice, epee).join().stock());                       // reserve des la vente
        SalesManager.Sale s = sales.list(alice, cid, null, 10, 0).join().get(0);
        assertEquals("PENDING", s.status());
        assertNotNull(s.invoiceId());
        invoices.pay(carl, s.invoiceId()).join();
        assertEquals("PAID", sales.list(alice, cid, null, 10, 0).join().get(0).status());
        assertEquals(600, bank.businessBalance(alice, cid).join());
        assertEquals("BAD_STATE", code(() -> sales.cancel(alice, sale).join()));      // deja payee : remboursement a part

        // seconde vente : le client refuse -> stock rendu, vente annulee
        long s2 = sales.checkout(alice, cid, List.of(new SalesManager.Item(epee, 1)), AccountRef.personal(carl.id()), "Carl").join();
        assertEquals(2, stock.get(alice, epee).join().stock());
        long inv2 = sales.list(alice, cid, null, 10, 0).join().get(0).invoiceId();
        invoices.refuse(carl, inv2).join();
        assertEquals(3, stock.get(alice, epee).join().stock());
        assertEquals("CANCELLED", sales.list(alice, cid, null, 10, 0).join().get(0).status());

        // troisieme : annulee par la caisse -> facture annulee et stock rendu
        long s3 = sales.checkout(alice, cid, List.of(new SalesManager.Item(epee, 1)), AccountRef.personal(carl.id()), "Carl").join();
        sales.cancel(alice, s3).join();
        assertEquals(3, stock.get(alice, epee).join().stock());
        assertEquals("CANCELLED", invoices.issuedByCompany(alice, cid, null, 10).join().get(0).status());
        assertEquals(List.of(), new TransactionManager(db).verifyBalances().join());
    }

    @Test
    void accountingReportAndJournalMatchTheRealData() {
        long cid = company();
        long epee = product(cid, "Epee", 300, 120, 5);         // stock initial : 5 x 120 = 600
        bank.deposit(carl, UUID.randomUUID(), 5000).join();
        sales.checkout(alice, cid, List.of(new SalesManager.Item(epee, 1)), AccountRef.personal(carl.id()), "Carl").join();
        invoices.pay(carl, sales.list(alice, cid, null, 5, 0).join().get(0).invoiceId()).join();
        employees.setPermissions(alice, cid, bob.id(), Permission.CASHIER.bit()).join();
        sales.checkout(bob, cid, List.of(new SalesManager.Item(epee, 1)), null, null).join(); // comptoir
        acct.addManual(alice, cid, false, "Loyer", "Loyer de la boutique", 50).join();
        acct.addManual(alice, cid, true, "Aide", "Aide du royaume", 20).join();
        // facture hors caisse encaissee : revenu autre
        bank.deposit(carl, UUID.randomUUID(), 1000).join();
        long inv = invoices.create(alice, cid, AccountRef.personal(carl.id()), "Reparation", 70, null).join();
        invoices.pay(carl, inv).join();

        long to = clock.get() + 1000;
        AccountingManager.Report r = acct.report(alice, cid, 0, to).join();
        assertEquals(600, r.salesRevenue());       // 2 ventes de 300
        assertEquals(2, r.salesCount());
        assertEquals(70, r.otherRevenue());
        assertEquals(20, r.manualIncome());
        assertEquals(690, r.revenue());
        assertEquals(240, r.costOfGoods());        // 2 x 120
        assertEquals(50, r.manualExpenses());
        assertEquals(290, r.expenses());
        assertEquals(400, r.result());
        assertEquals(360, r.grossMargin());
        assertEquals(3 * 120, r.stockValue());     // 3 epees restantes
        assertEquals(370, r.bankBalance());        // 300 + 70 ; les especes et les ecritures manuelles ne bougent pas la banque
        assertEquals(r.bankBalance() + r.stockValue(), r.assets());
        assertEquals(1, acct.topProducts(alice, cid, 0, to, 5).join().size());
        assertEquals(2L, acct.topProducts(alice, cid, 0, to, 5).join().get(0).qty());

        List<AccountingManager.JournalEntry> j = acct.journal(alice, cid, 0, to, 100).join();
        assertTrue(j.stream().anyMatch(e -> e.debit().startsWith("512") && e.credit().startsWith("707") && e.amount() == 300));
        assertTrue(j.stream().anyMatch(e -> e.debit().startsWith("530") && e.credit().startsWith("707")));
        assertTrue(j.stream().anyMatch(e -> e.debit().startsWith("603") && e.amount() == 120));
        assertTrue(j.stream().anyMatch(e -> e.credit().startsWith("401") && e.amount() == 600));
        assertTrue(j.stream().anyMatch(e -> e.credit().startsWith("706") && e.amount() == 70));
        // chaque ecriture est equilibree : un debit, un credit, meme montant
        assertTrue(j.stream().allMatch(e -> e.amount() > 0 && !e.debit().equals(e.credit())));

        // droits : l'employe sans droit de compta ne voit rien ; le patron supprime une ecriture manuelle (tracee)
        assertEquals("FORBIDDEN", code(() -> acct.report(bob, cid, 0, to).join()));
        assertEquals("FORBIDDEN", code(() -> acct.addManual(bob, cid, true, "X", "Interdit", 5).join()));
        long entry = acct.manualEntries(alice, cid, 10).join().get(0).id();
        acct.deleteManual(alice, entry).join();
        assertEquals(1, acct.manualEntries(alice, cid, 10).join().size());
        // une autre periode est vide
        assertEquals(0, acct.report(alice, cid, to + 10, to + 20).join().salesRevenue());
        assertEquals(List.of(), new TransactionManager(db).verifyBalances().join());
    }

    @Test
    void marginSetAtCreationFollowsTheCostAndOreBuybackPaysThePlayer() {
        long cid = company();
        // marge de 25 % saisie a la creation : prix = cout + 25 %
        long fer = stock.create(alice, cid, "FER", "Lingot de fer", 0, 8, 0, 0, 2500, 6).join();
        StockManager.Product p = stock.get(alice, fer).join();
        assertEquals(10, p.price());                 // 8 + 25 %
        assertEquals(2500, p.marginBp());
        assertEquals(6, p.buyback());
        assertEquals(StockManager.priceFor(7, 2500), 9);   // arrondi au superieur : 8,75 -> 9
        // l'employe sans droit de stock ne rachete rien ; le patron fixe le tarif mais aussi bien un article sans tarif refuse
        long bois = stock.create(alice, cid, "BOIS", "Bois", 3, 1, 0, 0).join();
        db.write(c -> { Records.touchPlayer(c, carl.id(), carl.name(), 1); return null; }).join();
        bank.deposit(alice, UUID.randomUUID(), 1000).join();
        bank.businessDeposit(alice, cid, UUID.randomUUID(), 500).join();
        assertEquals("NO_BUYBACK", code(() -> buybacks.buy(alice, bois, carl.id(), 5).join()));
        assertEquals("FORBIDDEN", code(() -> buybacks.buy(bob, fer, carl.id(), 5).join()));
        employees.setPermissions(alice, cid, bob.id(), Permission.MANAGE_STOCK.bit()).join();
        assertEquals("FORBIDDEN", code(() -> buybacks.buy(bob, fer, bob.id(), 5).join()));          // pas de rachat a soi-meme
        assertEquals("BAD_QTY", code(() -> buybacks.buy(bob, fer, carl.id(), 0).join()));
        // 20 lingots a 6 PO = 120 PO payes a Carl, stock +20, cout moyen = 6
        long before = bank.personalBalance(carl).join();
        long id = buybacks.buy(bob, fer, carl.id(), 20).join();
        assertEquals(before + 120, bank.personalBalance(carl).join());
        assertEquals(380, bank.businessBalance(alice, cid).join());
        StockManager.Product after = stock.get(alice, fer).join();
        assertEquals(20, after.stock());
        assertEquals(6, after.cost());
        assertEquals(8, after.price());             // le prix suit la marge : 6 + 25 % = 7,5 -> 8
        BuybackManager.Buyback r = buybacks.list(alice, cid, 5).join().get(0);
        assertEquals(id, r.id());
        assertEquals("R-000001", r.number());
        assertEquals(120, r.total());
        // le patron change le tarif : l'ancien rachat garde son prix, le nouveau est recalcule en cout moyen
        stock.update(alice, fer, "Lingot de fer", 0, 0, true, 2500, 10).join();
        buybacks.buy(alice, fer, carl.id(), 20).join();
        assertEquals(8, stock.get(alice, fer).join().cost());   // (20 x 6 + 20 x 10) / 40
        assertEquals(10, stock.get(alice, fer).join().price()); // 8 + 25 %
        // fonds insuffisants : rien n'est enregistre
        assertEquals("INSUFFICIENT_FUNDS", code(() -> buybacks.buy(alice, fer, carl.id(), 10_000).join()));
        assertEquals(40, stock.get(alice, fer).join().stock());
        assertEquals(2, buybacks.list(alice, cid, 10).join().size());
        // compta : le rachat est une entree de stock payee par la banque, pas une charge
        long to = clock.get() + 1000;
        AccountingManager.Report rep = acct.report(alice, cid, 0, to).join();
        assertEquals(320, rep.buybackTotal());
        assertEquals(0, rep.expenses());
        assertEquals(40 * 8, rep.stockValue());
        List<AccountingManager.JournalEntry> j = acct.journal(alice, cid, 0, to, 50).join();
        assertEquals(2, j.stream().filter(e -> e.debit().startsWith("31") && e.credit().startsWith("512")).count());
        assertTrue(j.stream().noneMatch(e -> e.credit().startsWith("401") && e.label().contains("fer")));
        assertEquals(List.of(), new TransactionManager(db).verifyBalances().join());
    }
}
