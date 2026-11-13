package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class BankFlowTest {
    @TempDir Path tmp;
    Database db;
    CompanyManager companies;
    EmployeeManager employees;
    BankManager bank;
    TransactionManager tx;
    PayrollManager payroll;

    final Actor admin = new Actor(UUID.randomUUID(), "Admin", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);
    final Actor carl = new Actor(UUID.randomUUID(), "Carl", false);

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        companies = new CompanyManager(db);
        employees = new EmployeeManager(db);
        bank = new BankManager(db);
        tx = new TransactionManager(db);
        payroll = new PayrollManager(db);
    }

    @AfterEach
    void tearDown() { db.close(); }

    static String code(Supplier<?> s) {
        CompletionException e = assertThrows(CompletionException.class, s::get);
        return ((EconomyException) EconomyException.unwrap(e)).code();
    }

    long company() {
        long req = companies.submitRequest(alice, "Forge Royale", "", "Artisanat", "").join();
        return companies.accept(admin, req, "").join();
    }

    void assertConsistent() { assertEquals(List.of(), tx.verifyBalances().join()); }

    @Test
    void depositWithdrawAndIdempotence() {
        UUID id = UUID.randomUUID();
        TxResult r1 = bank.deposit(alice, id, 1000).join();
        TxResult r2 = bank.deposit(alice, id, 1000).join(); // double clic / rejeu
        assertTrue(r2.replayed());
        assertEquals(r1.id(), r2.id());
        assertEquals(1000, bank.personalBalance(alice).join());
        assertEquals("TX_CONFLICT", code(() -> bank.deposit(alice, id, 999).join()));
        assertEquals("INSUFFICIENT_FUNDS", code(() -> bank.withdraw(alice, UUID.randomUUID(), 1001).join()));
        bank.withdraw(alice, UUID.randomUUID(), 400).join();
        assertEquals(600, bank.personalBalance(alice).join());
        assertEquals("BAD_AMOUNT", code(() -> bank.deposit(alice, UUID.randomUUID(), -5).join()));
        assertEquals("BAD_AMOUNT", code(() -> bank.deposit(alice, UUID.randomUUID(), 0).join()));
        assertEquals("CASH_LIMIT", code(() -> bank.deposit(alice, UUID.randomUUID(), BankManager.MAX_CASH_OPERATION + 1).join()));
        assertConsistent();
    }

    @Test
    void transfersConserveMoney() {
        bank.deposit(alice, UUID.randomUUID(), 1000).join();
        bank.deposit(bob, UUID.randomUUID(), 1).join();
        bank.transfer(alice, UUID.randomUUID(), bob.id(), 300, "merci").join();
        assertEquals(700, bank.personalBalance(alice).join());
        assertEquals(301, bank.personalBalance(bob).join());
        assertEquals("TARGET_UNKNOWN", code(() -> bank.transfer(alice, UUID.randomUUID(), carl.id(), 1, "").join()));
        assertEquals("BAD_TX", code(() -> bank.transfer(alice, UUID.randomUUID(), alice.id(), 1, "").join()));
        assertConsistent();
    }

    @Test
    void concurrentTransfersNeverOverdraw() {
        bank.deposit(alice, UUID.randomUUID(), 1000).join();
        bank.deposit(bob, UUID.randomUUID(), 1).join();
        List<CompletableFuture<Object>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) { // 200 x 10 = 2000 demandes pour 1000 disponibles
            fs.add(CompletableFuture.supplyAsync(() -> {
                try { return bank.transfer(alice, UUID.randomUUID(), bob.id(), 10, "").join(); }
                catch (CompletionException e) { return e; }
            }));
        }
        fs.forEach(CompletableFuture::join);
        assertEquals(0, bank.personalBalance(alice).join());
        assertEquals(1001, bank.personalBalance(bob).join());
        assertConsistent();
    }

    @Test
    void businessPermissionsAndPayroll() {
        long id = company();
        bank.deposit(alice, UUID.randomUUID(), 1000).join();
        bank.businessDeposit(alice, id, UUID.randomUUID(), 500).join();
        employees.hire(alice, id, bob.id(), bob.name(), 200).join();
        employees.hire(alice, id, carl.id(), carl.name(), 200).join();

        assertEquals("FORBIDDEN", code(() -> bank.businessWithdraw(bob, id, UUID.randomUUID(), 10).join())); // employe
        assertEquals("FORBIDDEN", code(() -> bank.businessBalance(bob, id).join()));

        long period = 42;
        PayrollManager.Report r = payroll.runCompany(id, period).join();
        assertEquals(2, r.paid());
        assertEquals(500 - 400, bank.businessBalance(alice, id).join());
        PayrollManager.Report again = payroll.runCompany(id, period).join(); // rejeu : aucun double paiement
        assertEquals(0, again.paid());
        assertEquals(2, again.alreadyPaid());
        assertEquals(200, bank.personalBalance(bob).join());

        PayrollManager.Report next = payroll.runCompany(id, period + 1).join(); // 100 en caisse pour 2x200
        assertEquals(0, next.paid());
        assertEquals(2, next.unpaid());
        assertConsistent();
    }

    @Test
    void suspendedCompanyCannotSpend() {
        long id = company();
        bank.deposit(alice, UUID.randomUUID(), 100).join();
        bank.businessDeposit(alice, id, UUID.randomUUID(), 100).join();
        companies.setStatus(admin, id, CompanyStatus.SUSPENDED, "Enquete").join();
        assertEquals("COMPANY_NOT_ACTIVE", code(() -> bank.businessWithdraw(alice, id, UUID.randomUUID(), 10).join()));
        assertConsistent();
    }
}
