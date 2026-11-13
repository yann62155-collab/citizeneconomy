package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Tests obligatoires restants : taxe personnelle, paie automatique, redemarrage et conservation des donnees, notifications. */
class FinalChecksTest {
    static final ZoneId Z = ZoneId.of("Europe/Paris");
    @TempDir Path tmp;

    final Actor admin = new Actor(UUID.randomUUID(), "Admin", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);

    static long at(String iso) { return LocalDateTime.parse(iso).atZone(Z).toInstant().toEpochMilli(); }

    Database open() throws Exception {
        Database db = new Database(tmp.resolve("eco.db"), tmp.resolve("backups"));
        db.open();
        return db;
    }

    @Test
    void personalIncomeTaxOnSelectedIncomeWithoutDoubleTaxation() throws Exception {
        Database db = open();
        try {
            db.write(c -> { Settings.set(c, Settings.TAX_ZONE, "Europe/Paris", "test", 0); return null; }).join();
            AtomicLong clock = new AtomicLong(at("2026-11-10T12:00:00"));
            CompanyManager companies = new CompanyManager(db);
            TaxManager tax = new TaxManager(db, clock::get);
            PlayerManager players = new PlayerManager(db);
            for (Actor a : List.of(admin, alice, bob)) players.touch(a).join();
            long cid = companies.accept(admin, companies.submitRequest(alice, "Forge", "", "Artisanat", "").join(), "").join();

            tax.closePeriods().join();
            tax.setPersonalIncomeTypes(admin, Set.of(TxType.SALARY)).join();
            tax.setTax(admin, TaxKind.PERSONAL_INCOME, TaxBase.SELECTED_INCOME, 1000, true, TaxMode.DECLARATION).join(); // 10 %, effet dimanche
            // salaire de 800 PO verse la semaine suivante, plus un virement de 5000 qui ne doit PAS etre impose
            db.write(c -> {
                Sql.insert(c, "INSERT INTO transactions(tx_uuid,type,taxable,amount,source_type,source_id,target_type,target_id,created_at) VALUES (?, 'SALARY', 1, 800, 'BUSINESS', ?, 'PERSONAL', ?, ?)",
                        UUID.randomUUID().toString(), Long.toString(cid), bob.id().toString(), at("2026-11-17T10:00:00"));
                Sql.insert(c, "INSERT INTO transactions(tx_uuid,type,taxable,amount,source_type,source_id,target_type,target_id,created_at) VALUES (?, 'TRANSFER', 0, 5000, 'PERSONAL', ?, 'PERSONAL', ?, ?)",
                        UUID.randomUUID().toString(), alice.id().toString(), bob.id().toString(), at("2026-11-17T11:00:00"));
                return null;
            }).join();
            clock.set(at("2026-11-15T00:05:00"));
            tax.closePeriods().join();
            clock.set(at("2026-11-22T00:05:00"));
            tax.closePeriods().join();
            List<TaxDeclaration> mine = tax.myDeclarations(bob, 10).join();
            assertEquals(1, mine.size());
            assertEquals(TaxKind.PERSONAL_INCOME, mine.get(0).kind());
            assertEquals(800, mine.get(0).baseAmount());
            assertEquals(80, mine.get(0).amount());
            assertEquals(0, tax.myDeclarations(alice, 10).join().size());   // le virement n'a rien declenche chez Alice
            tax.closePeriods().join();
            assertEquals(1, tax.myDeclarations(bob, 10).join().size());      // pas de doublon au rejeu

            // paiement : Bob a de quoi payer -> l'argent va a la tresorerie royale
            new BankManager(db).deposit(bob, UUID.randomUUID(), 300).join();
            tax.pay(bob, mine.get(0).id()).join();
            assertEquals(80, new TreasuryManager(db).balance(admin).join());
            assertEquals(220, new BankManager(db).personalBalance(bob).join());
            assertEquals(List.of(), new TransactionManager(db).verifyBalances().join());
        } finally { db.close(); }
    }

    @Test
    void schedulerPaysSalariesOncePerSunday() throws Exception {
        Database db = open();
        try {
            db.write(c -> { Settings.set(c, Settings.TAX_ZONE, "Europe/Paris", "test", 0); return null; }).join();
            AtomicLong clock = new AtomicLong(at("2026-11-10T12:00:00"));
            CompanyManager companies = new CompanyManager(db);
            EmployeeManager employees = new EmployeeManager(db);
            BankManager bank = new BankManager(db);
            PlayerManager players = new PlayerManager(db);
            for (Actor a : List.of(admin, alice, bob)) players.touch(a).join();
            long cid = companies.accept(admin, companies.submitRequest(alice, "Auberge", "", "Taverne", "").join(), "").join();
            employees.hire(alice, cid, bob.id(), bob.name(), 100).join();
            bank.deposit(alice, UUID.randomUUID(), 1000).join();
            bank.businessDeposit(alice, cid, UUID.randomUUID(), 1000).join();

            List<String> failures = new java.util.ArrayList<>();
            EconomyScheduler s = new EconomyScheduler(db, new TaxManager(db, clock::get), new PayrollManager(db), new InvoiceManager(db, clock::get),
                    new NotificationManager(db), clock::get, failures::add);
            s.tick();                                                        // premier passage : alignement, aucune paie retroactive
            assertEquals(0, bank.personalBalance(bob).join());
            clock.set(at("2026-11-15T00:10:00"));                           // dimanche
            s.tick();
            assertEquals(100, bank.personalBalance(bob).join());
            s.tick();
            clock.set(at("2026-11-15T09:00:00"));
            s.tick();
            assertEquals(100, bank.personalBalance(bob).join());             // jamais deux fois la meme semaine
            assertEquals(900, bank.businessBalance(alice, cid).join());
            clock.set(at("2026-11-22T00:10:00"));
            s.tick();
            assertEquals(200, bank.personalBalance(bob).join());
            assertEquals(List.of(), failures);
            assertEquals(List.of(), new TransactionManager(db).verifyBalances().join());
        } finally { db.close(); }
    }

    @Test
    void dataSurvivesServerRestart() throws Exception {
        UUID repeated = UUID.randomUUID();
        long cid;
        Database db = open();
        try {
            PlayerManager players = new PlayerManager(db);
            for (Actor a : List.of(admin, alice, bob)) players.touch(a).join();
            CompanyManager companies = new CompanyManager(db);
            cid = companies.accept(admin, companies.submitRequest(alice, "Forge Royale", "Armes", "Forge", "marteau").join(), "bienvenue").join();
            new EmployeeManager(db).hire(alice, cid, bob.id(), bob.name(), 50).join();
            BankManager bank = new BankManager(db);
            bank.deposit(alice, repeated, 700).join();
            bank.businessDeposit(alice, cid, UUID.randomUUID(), 200).join();
            new TreasuryManager(db).addRevenue(admin, UUID.randomUUID(), 40, "amende").join();
            new TreasuryManager(db).spend(admin, UUID.randomUUID(), AccountRef.personal(bob.id()), 10, "prime").join();
        } finally { db.close(); }

        // "redemarrage" : nouvelle connexion sur le meme fichier
        db = open();
        try {
            BankManager bank = new BankManager(db);
            assertEquals(500, bank.personalBalance(alice).join());
            assertEquals(10, bank.personalBalance(bob).join());
            assertEquals(200, bank.businessBalance(alice, cid).join());
            assertEquals(30, new TreasuryManager(db).balance(admin).join());
            List<Company> mine = new CompanyManager(db).companiesOf(alice.id()).join();
            assertEquals(1, mine.size());
            assertEquals("Forge Royale", mine.get(0).name());
            assertEquals(CompanyStatus.ACTIVE, mine.get(0).status());
            assertEquals(2, new EmployeeManager(db).members(alice, cid).join().size());
            assertEquals(List.of(), new TransactionManager(db).verifyBalances().join());
            // l'idempotence survit au redemarrage : rejouer la meme transaction ne credite rien
            assertTrue(bank.deposit(alice, repeated, 700).join().replayed());
            assertEquals(500, bank.personalBalance(alice).join());
            // la base se rouvre sans nouvelle migration destructrice
            assertTrue(new AdminManager(db).dashboard(admin).join().players() >= 3);
        } finally { db.close(); }
    }

    @Test
    void notificationsAreStoredDeliveredOnceAndReadable() throws Exception {
        Database db = open();
        try {
            new PlayerManager(db).touch(admin).join();
            new PlayerManager(db).touch(alice).join();
            new TreasuryManager(db).addRevenue(admin, UUID.randomUUID(), 100, "amende").join();
            new TreasuryManager(db).spend(admin, UUID.randomUUID(), AccountRef.personal(alice.id()), 25, "recompense").join();
            NotificationManager n = new NotificationManager(db);
            assertTrue(n.playersWithPending().join().contains(alice.id()));
            List<NotificationManager.Notif> list = n.undelivered(alice.id(), 10).join();
            assertEquals(1, list.size());
            assertTrue(list.get(0).message().contains("25"));
            n.markDelivered(list.stream().map(NotificationManager.Notif::id).toList()).join();
            assertEquals(0, n.undelivered(alice.id(), 10).join().size());      // jamais livree deux fois
            assertFalse(n.playersWithPending().join().contains(alice.id()));
            assertEquals(1, n.history(alice.id(), 10, 0).join().size());       // reste consultable
            assertEquals(1, n.unreadCount(alice.id()).join());
            n.markAllRead(alice.id()).join();
            assertEquals(0, n.unreadCount(alice.id()).join());
        } finally { db.close(); }
    }
}
