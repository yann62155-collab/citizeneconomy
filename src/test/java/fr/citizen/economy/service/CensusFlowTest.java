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

    @Test
    void playerFillsValidatesThenIsLocked() {
        census.fill(smith, " Aldric ", "Valmont", "32").join();
        census.validate(smith).join();
        CensusManager.Entry e = census.get(smith.id()).join().orElseThrow();
        assertEquals("Aldric", e.firstName());
        assertEquals(32, e.age());
        assertTrue(e.validated());
        assertEquals("LOCKED", code(() -> census.fill(smith, "Autre", "Nom", "20").join()));
        assertEquals("LOCKED", code(() -> census.validate(smith).join()));
    }

    @Test
    void invalidInputsAreRefused() {
        assertEquals("BAD_TEXT", code(() -> census.fill(smith, "", "Valmont", "32").join()));
        assertEquals("BAD_TEXT", code(() -> census.fill(smith, "Ald§ric", "Valmont", "32").join()));
        assertEquals("BAD_AGE", code(() -> census.fill(smith, "Aldric", "Valmont", "abc").join()));
        assertEquals("BAD_AGE", code(() -> census.fill(smith, "Aldric", "Valmont", "0").join()));
        assertEquals("NOT_FOUND", code(() -> census.validate(nobody).join()));
    }

    @Test
    void professionComesFromCompanyUnlessAdminSetsOne() {
        census.fill(smith, "Aldric", "Valmont", "32").join();
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
    void adminToolsAndPermissions() {
        census.fill(smith, "Aldric", "Valmont", "32").join();
        census.validate(smith).join();
        assertEquals("FORBIDDEN", code(() -> census.adminUnlock(smith, smith.id()).join()));
        census.adminUnlock(admin, smith.id()).join();
        census.fill(smith, "Aldric", "Valmont", "34").join();
        census.adminValidate(admin, smith.id()).join();
        assertTrue(census.get(smith.id()).join().orElseThrow().validated());
        assertEquals(3, census.list(admin, 50).join().size());
        census.adminDelete(admin, smith.id()).join();
        assertTrue(census.get(smith.id()).join().isEmpty());
        assertEquals("NOT_FOUND", code(() -> census.adminDelete(admin, smith.id()).join()));
    }
}
