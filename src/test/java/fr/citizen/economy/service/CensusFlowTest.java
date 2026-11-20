package fr.citizen.economy.service;

import fr.citizen.economy.domain.Actor;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class CensusFlowTest {
    @TempDir Path tmp;
    Database db;
    CensusManager census;
    CompanyManager companies;
    PlayerManager players;

    final Actor admin = new Actor(UUID.randomUUID(), "Admin", true);
    final Actor smith = new Actor(UUID.randomUUID(), "Smith", false);
    final Actor nobody = new Actor(UUID.randomUUID(), "Nobody", false);

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        census = new CensusManager(db);
        companies = new CompanyManager(db);
        players = new PlayerManager(db);
        players.touch(admin).join();
        players.touch(smith).join();
        players.touch(nobody).join();
    }

    @AfterEach
    void tearDown() { db.close(); }

    static String code(Supplier<?> s) {
        CompletionException e = assertThrows(CompletionException.class, s::get);
        return ((EconomyException) EconomyException.unwrap(e)).code();
    }

    RoyalStaffManager staff;
    final Actor clerk = new Actor(UUID.randomUUID(), "Greffier", false);

    void hireClerk() {
        players.touch(clerk).join();
        staff = new RoyalStaffManager(db);
        staff.hire(admin, clerk.id(), clerk.name(), "Greffier", 50).join();
    }

    @Test
    void onlyRoyalStaffAndAdminsDoTheCensus() {
        hireClerk();
        // un simple joueur ne peut rien faire, ni pour lui-meme
        assertEquals("FORBIDDEN", code(() -> census.adminSet(smith, smith.id(), "Aldric", "Valmont", "32", "").join()));
        assertEquals("FORBIDDEN", code(() -> census.adminValidate(smith, smith.id()).join()));
        assertEquals("FORBIDDEN", code(() -> census.list(smith, 50).join()));
        assertFalse(census.canCensus(smith).join());
        // l'employe de la couronne recense un citoyen, puis valide
        assertTrue(census.canCensus(clerk).join());
        census.adminSet(clerk, smith.id(), " Aldric ", "Valmont", "32", "").join();
        census.adminValidate(clerk, smith.id()).join();
        CensusManager.Entry e = census.get(smith.id()).join().orElseThrow();
        assertEquals("Aldric", e.firstName());
        assertTrue(e.validated());
        assertEquals(4, census.list(clerk, 50).join().size());
        // renvoye : plus aucun droit
        staff.fire(admin, clerk.id()).join();
        assertFalse(census.canCensus(clerk).join());
        assertEquals("FORBIDDEN", code(() -> census.adminUnlock(clerk, smith.id()).join()));
        // admin et Conseil royal gardent l'acces
        assertTrue(census.canCensus(admin).join());
        assertTrue(census.canCensus(Actor.council()).join());
    }

    @Test
    void invalidInputsAreRefused() {
        assertEquals("BAD_TEXT", code(() -> census.adminSet(admin, smith.id(), "", "Valmont", "32", "").join()));
        assertEquals("BAD_TEXT", code(() -> census.adminSet(admin, smith.id(), "Ald§ric", "Valmont", "32", "").join()));
        assertEquals("BAD_AGE", code(() -> census.adminSet(admin, smith.id(), "Aldric", "Valmont", "abc", "").join()));
        assertEquals("BAD_AGE", code(() -> census.adminSet(admin, smith.id(), "Aldric", "Valmont", "0", "").join()));
        assertEquals("NOT_FOUND", code(() -> census.adminValidate(admin, nobody.id()).join()));
    }

    @Test
    void professionComesFromCompanyUnlessAdminSetsOne() {
        census.adminSet(admin, smith.id(), "Aldric", "Valmont", "32", "").join();
        assertEquals(CensusManager.NO_JOB, census.cards().join().get(smith.id()).profession());

        long req = companies.submitRequest(smith, "Forge Royale", "Armes et armures", "Forgeron", "").join();
        companies.accept(admin, req, "").join();
        Map<UUID, CensusManager.Card> cards = census.cards().join();
        assertEquals("Forgeron", cards.get(smith.id()).profession());
        assertEquals("Forgeron", census.autoProfession(smith.id()).join());

        census.adminSet(admin, smith.id(), "Aldric", "Valmont", "33", "Maître d'armes").join();
        assertEquals("Maître d'armes", census.cards().join().get(smith.id()).profession());
        assertEquals(33, census.get(smith.id()).join().orElseThrow().age());
    }

    @Test
    void unlockValidateDeleteTools() {
        census.adminSet(admin, smith.id(), "Aldric", "Valmont", "32", "").join();
        census.adminValidate(admin, smith.id()).join();
        census.adminUnlock(admin, smith.id()).join();
        assertFalse(census.get(smith.id()).join().orElseThrow().validated());
        census.adminValidate(admin, smith.id()).join();
        assertEquals(3, census.list(admin, 50).join().size());
        census.adminDelete(admin, smith.id()).join();
        assertTrue(census.get(smith.id()).join().isEmpty());
        assertEquals("NOT_FOUND", code(() -> census.adminDelete(admin, smith.id()).join()));
    }

    @Test
    void royalStaffIsPaidFromTheTreasuryOncePerPeriod() {
        hireClerk();
        TreasuryManager treasury = new TreasuryManager(db);
        PayrollManager payroll = new PayrollManager(db);
        BankManager bank = new BankManager(db);
        // tresorerie vide : impaye, aucune transaction
        assertEquals("[0, 0, 1]", java.util.Arrays.toString(payroll.runRoyal(1).join()));
        treasury.addRevenue(admin, UUID.randomUUID(), 500, "Amende").join();
        assertEquals("[1, 0, 0]", java.util.Arrays.toString(payroll.runRoyal(2).join()));
        assertEquals(50, bank.personalBalance(clerk).join());
        assertEquals(450, treasury.balance(admin).join());
        // meme periode relancee : jamais deux fois
        assertEquals("[0, 1, 0]", java.util.Arrays.toString(payroll.runRoyal(2).join()));
        assertEquals(50, bank.personalBalance(clerk).join());
        assertEquals("FORBIDDEN", code(() -> staff.hire(smith, smith.id(), "Smith", "Scribe", 10).join()));
        assertEquals("ALREADY_STAFF", code(() -> staff.hire(admin, clerk.id(), "Greffier", "Greffier", 10).join()));
        assertEquals("BAD_AMOUNT", code(() -> staff.update(admin, clerk.id(), "", 2_000_000).join()));
        staff.update(admin, clerk.id(), "", 80).join();
        assertEquals("Greffier", staff.list(admin).join().get(0).title());
        assertEquals(80, staff.list(admin).join().get(0).salary());
    }

    @Test
    void emblemsAreCheckedAndAdminsCanChangeThemOnExistingCompanies() {
        assertEquals("", Emblems.parse(""));
        assertEquals("FOR", Emblems.parse(" Forgeron "));
        assertEquals("RES", Emblems.parse("res")); assertEquals("RES", Emblems.parse("RESTAURANT"));
        assertEquals("HOP", Emblems.parse("Hôpital")); assertEquals("HOP", Emblems.parse("hop"));
        assertThrows(EconomyException.class, () -> Emblems.parse("sorcier"));
        assertEquals("BAD_EMBLEM", code(() -> companies.submitRequest(smith, "Mauvais Embleme", "Description ok", "Forge", "sorcier").join()));
        long req = companies.submitRequest(smith, "Forge Royale", "Armes et armures", "Forgeron", "Forgeron").join();
        long id = companies.accept(admin, req, "").join();
        assertEquals("FOR", companies.get(admin, id).join().emblem());
        // un patron ne change pas son propre emblème (sinon il se retirerait les restrictions)
        assertEquals("FORBIDDEN", code(() -> companies.setEmblem(smith, id, "aucun").join()));
        companies.setEmblem(admin, id, "Aucun").join();
        assertEquals("AUC", companies.get(admin, id).join().emblem());
        companies.setEmblem(Actor.council(), id, "taverne").join();
        assertEquals("TAV", companies.get(admin, id).join().emblem());
        assertEquals("BAD_EMBLEM", code(() -> companies.setEmblem(admin, id, "dragon").join()));
    }
}
