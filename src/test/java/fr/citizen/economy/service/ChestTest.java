package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ChestTest {
    @TempDir Path tmp;
    Database db;
    Economy eco;
    final Actor admin = new Actor(UUID.randomUUID(), "Roi", true);
    final Actor boss = new Actor(UUID.randomUUID(), "Patron", false);
    final Actor worker = new Actor(UUID.randomUUID(), "Employe", false);
    final Actor chief = new Actor(UUID.randomUUID(), "Gerant", false);
    final Actor stranger = new Actor(UUID.randomUUID(), "Passant", false);
    long tavern;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        eco = new Economy(db, s -> { });
        for (Actor a : List.of(admin, boss, worker, chief, stranger)) eco.players.touch(a).join();
        eco.companies.submitRequest(boss, "Taverne du Lion", "Une taverne", "Taverne", "TAV").join();
        long rid = eco.companies.pendingRequests(admin).join().get(0).id();
        eco.companies.accept(admin, rid, "ok").join();
        tavern = eco.companies.companiesOf(boss.id()).join().get(0).id();
        eco.employees.hire(boss, tavern, worker.id(), worker.name(), 0).join();
        eco.employees.hire(boss, tavern, chief.id(), chief.name(), 0).join();
        eco.employees.promoteToManager(boss, tavern, chief.id()).join();
    }

    @AfterEach
    void tearDown() { db.close(); }

    @Test
    void onlyTheBossOrAManagerCanRegisterAChestAndItIsKeptOnce() {
        assertTrue(eco.chests.register(boss, tavern, "minecraft:overworld", 10, 64, 10).join() > 0);
        assertTrue(eco.chests.register(chief, tavern, "minecraft:overworld", 11, 64, 10).join() > 0);
        CompletionException e = assertThrows(CompletionException.class, () -> eco.chests.register(worker, tavern, "minecraft:overworld", 12, 64, 10).join());
        assertTrue(e.getCause().getMessage().contains("patron"));
        assertThrows(CompletionException.class, () -> eco.chests.register(stranger, tavern, "minecraft:overworld", 13, 64, 10).join());
        assertThrows(CompletionException.class, () -> eco.chests.register(boss, tavern, "minecraft:overworld", 10, 64, 10).join()); // deja pris
        assertEquals(2, eco.chests.all().join().size());
        assertEquals("Taverne du Lion", eco.chests.activeCompanyName(tavern).join());
    }

    @Test
    void locksAnswerFromMemoryAndFollowHiringAndFiring() {
        eco.chests.register(boss, tavern, "minecraft:overworld", 10, 64, 10).join();
        ChestLocks locks = new ChestLocks();
        locks.loadChests(eco.chests.all().join());
        locks.loadMembers(eco.chests.snapshot().join());
        assertEquals(tavern, locks.companyAt("minecraft:overworld", 10, 64, 10));
        assertNull(locks.companyAt("minecraft:overworld", 10, 65, 10));
        assertNull(locks.companyAt("minecraft:the_nether", 10, 64, 10));
        assertTrue(locks.isMember(boss.id(), tavern));
        assertTrue(locks.isMember(worker.id(), tavern));
        assertFalse(locks.isStaff(worker.id(), tavern));
        assertTrue(locks.isStaff(chief.id(), tavern));
        assertFalse(locks.isMember(stranger.id(), tavern));
        assertEquals("Taverne du Lion", locks.name(tavern));
        // licenciement : apres le rafraichissement, l'employe n'a plus acces
        eco.employees.fire(boss, tavern, worker.id()).join();
        locks.loadMembers(eco.chests.snapshot().join());
        assertFalse(locks.isMember(worker.id(), tavern));
        // casser le coffre le retire
        assertTrue(eco.chests.remove(boss, "minecraft:overworld", 10, 64, 10).join());
        assertFalse(eco.chests.remove(boss, "minecraft:overworld", 10, 64, 10).join());
        locks.remove("minecraft:overworld", 10, 64, 10);
        assertNull(locks.companyAt("minecraft:overworld", 10, 64, 10));
    }

    @Test
    void aChestOfAnInactiveCompanyCannotBeRegistered() {
        eco.companies.setStatus(admin, tavern, CompanyStatus.SUSPENDED, "test").join();
        assertThrows(CompletionException.class, () -> eco.chests.register(boss, tavern, "minecraft:overworld", 1, 64, 1).join());
    }

    @Test
    void anAdminFindsACompanyByNameOrNumber() {
        assertEquals(tavern, eco.chests.resolveActive("taverne du lion").join().id());
        assertEquals("Taverne du Lion", eco.chests.resolveActive(Long.toString(tavern)).join().name());
        assertThrows(CompletionException.class, () -> eco.chests.resolveActive("Inconnue").join());
        assertThrows(CompletionException.class, () -> eco.chests.resolveActive("1 OR 1=1").join());
    }
}
