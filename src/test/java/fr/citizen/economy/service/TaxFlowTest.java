package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TaxFlowTest {
    static final ZoneId Z = ZoneId.of("Europe/Paris");
    @TempDir Path tmp;
    Database db;
    AtomicLong clock;
    CompanyManager companies;
    EmployeeManager employees;
    BankManager bank;
    TaxManager tax;
    TreasuryManager treasury;

    final Actor admin = new Actor(UUID.randomUUID(), "Admin", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);

    static long at(String iso) { return LocalDateTime.parse(iso).atZone(Z).toInstant().toEpochMilli(); }

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        db.write(c -> { Settings.set(c, Settings.TAX_ZONE, "Europe/Paris", "test", 0); return null; }).join();
        clock = new AtomicLong(at("2026-11-10T12:00:00")); // mardi
        companies = new CompanyManager(db);
        employees = new EmployeeManager(db);
        bank = new BankManager(db);
        tax = new TaxManager(db, clock::get);
        treasury = new TreasuryManager(db);
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

    void invoicePayment(long companyId, long amount, long ts) {
        db.write(c -> Sql.insert(c, "INSERT INTO transactions(tx_uuid,type,taxable,amount,source_type,source_id,target_type,target_id,created_at) "
                + "VALUES (?, 'INVOICE_PAYMENT', 1, ?, 'PERSONAL', ?, 'BUSINESS', ?, ?)", UUID.randomUUID().toString(), amount, bob.id().toString(), Long.toString(companyId), ts)).join();
    }

    @Test
    void weeklyDeclarationOnSunday_noRetroactivity_manualPaymentToTreasury() {
        long id = company();
        assertEquals(0, tax.closePeriods().join());                         // 1re execution : alignement, aucune taxe retroactive
        tax.setTax(admin, TaxKind.BUSINESS_INCOME, TaxBase.REVENUE, 1000, true, TaxMode.DECLARATION).join(); // 10 %, effet dimanche 15/11
        invoicePayment(id, 5000, at("2026-11-12T10:00:00"));                // semaine en cours : taux pas encore en vigueur
        invoicePayment(id, 1000, at("2026-11-17T10:00:00"));                // semaine suivante : taxee

        clock.set(at("2026-11-15T00:05:00"));                               // cloture de la 1re semaine
        assertEquals(0, tax.closePeriods().join());                         // taux non applicable a cette periode

        clock.set(at("2026-11-22T00:05:00"));                               // dimanche suivant
        assertEquals(1, tax.closePeriods().join());
        assertEquals(0, tax.closePeriods().join());                         // rejeu : aucun doublon
        TaxDeclaration d = tax.companyDeclarations(alice, id, 10).join().get(0);
        assertEquals(1000, d.baseAmount());
        assertEquals(100, d.amount());                                      // 10 % de 1000
        assertEquals("DUE", d.status());

        // changement de taux : n'altere pas la declaration existante
        tax.setTax(admin, TaxKind.BUSINESS_INCOME, TaxBase.REVENUE, 5000, true, TaxMode.DECLARATION).join();
        assertEquals(100, tax.companyDeclarations(alice, id, 10).join().get(0).amount());

        // paiement manuel : il faut des fonds et le droit MANAGE_FINANCES
        bank.deposit(alice, UUID.randomUUID(), 500).join();
        bank.businessDeposit(alice, id, UUID.randomUUID(), 500).join();
        employees.hire(alice, id, bob.id(), bob.name(), 0).join();
        assertEquals("FORBIDDEN", code(() -> tax.pay(bob, d.id()).join()));
        tax.pay(alice, d.id()).join();
        assertEquals(100, treasury.balance(admin).join());                  // l'argent arrive dans la tresorerie royale
        assertEquals("BAD_STATE", code(() -> tax.pay(alice, d.id()).join())); // pas de double paiement
        assertEquals(400, bank.businessBalance(alice, id).join());
        assertEquals(List.of(), new TransactionManager(db).verifyBalances().join());
    }

    @Test
    void transfersAreNeverTaxedAndExemptionsAreHonoured() {
        long id = company();
        tax.closePeriods().join();
        tax.setTax(admin, TaxKind.BUSINESS_INCOME, TaxBase.REVENUE, 1000, true, TaxMode.DECLARATION).join();
        db.write(c -> Sql.insert(c, "INSERT INTO transactions(tx_uuid,type,amount,source_type,source_id,target_type,target_id,created_at) "
                + "VALUES (?, 'TRANSFER', 9999, 'PERSONAL', ?, 'BUSINESS', ?, ?)", UUID.randomUUID().toString(), bob.id().toString(), Long.toString(id), at("2026-11-17T10:00:00"))).join();
        clock.set(at("2026-11-22T00:05:00"));
        assertEquals(0, tax.closePeriods().join());                         // un simple transfert n'est pas un revenu
    }

    @Test
    void onlyAdminsConfigureTaxes() {
        assertEquals("FORBIDDEN", code(() -> tax.setTax(alice, TaxKind.SALARY, TaxBase.SALARY, 500, true, TaxMode.AUTO).join()));
        assertEquals("BAD_TAX", code(() -> tax.setTax(admin, TaxKind.SALARY, TaxBase.REVENUE, 500, true, TaxMode.AUTO).join()));
        assertEquals("BAD_RATE", code(() -> tax.setTax(admin, TaxKind.SALARY, TaxBase.SALARY, 10_001, true, TaxMode.AUTO).join()));
        assertEquals("FORBIDDEN", code(() -> treasury.balance(alice).join()));
        assertEquals("FORBIDDEN", code(() -> treasury.spend(alice, UUID.randomUUID(), AccountRef.personal(alice.id()), 10, "vol").join()));
    }
}
