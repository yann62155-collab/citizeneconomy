package fr.citizen.economy.platform;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.Economy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DiscordLinksTest {
    @TempDir Path tmp;
    Database db;
    Economy eco;
    final Actor admin = new Actor(UUID.randomUUID(), "Roi", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);
    final Actor gamer = new Actor(UUID.randomUUID(), ".Ma Gamertag", false);
    long forge, taverne;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        eco = new Economy(db, s -> { });
        for (Actor a : List.of(admin, alice, bob, gamer)) eco.players.touch(a).join();
        forge = company(alice, "Forge Royale", "Forge", "FOR");
        taverne = company(gamer, "Taverne du Lion", "Taverne", "TAV");
        eco.employees.hire(alice, forge, bob.id(), bob.name(), 0).join();
        FakeDiscordApi.synced.clear();
        FakeDiscordApi.ready = true; FakeDiscordApi.rolesOn = true;
        FakeDiscordApi.links = new LinkedHashMap<>();
        FakeDiscordApi.links.put("111111111111111111", List.of("alice"));
        FakeDiscordApi.links.put("222222222222222222", List.of("bob"));
        FakeDiscordApi.links.put("333333333333333333", List.of("ma gamertag"));
        FakeDiscordApi.links.put("444444444444444444", List.of("inconnu"));
    }

    @AfterEach
    void tearDown() { db.close(); }

    long company(Actor owner, String name, String sector, String emblem) {
        eco.companies.submitRequest(owner, name, "Entreprise de test", sector, emblem).join();
        long rid = eco.companies.pendingRequests(admin).join().get(0).id();
        eco.companies.accept(admin, rid, "ok").join();
        return eco.companies.companiesOf(owner.id()).join().get(0).id();
    }

    DiscordApiFacade fake() { return DiscordApiFacade.reflective("fr.citizen.economy.platform.FakeDiscordApi"); }

    @Test
    void jobsAreSentPerDiscordAccountThroughReflection() {
        DiscordLinks links = new DiscordLinks(eco, fake());
        assertTrue(links.available());
        DiscordLinks.SyncResult r = links.syncJobs();
        assertFalse(r.skipped(), r.reason());
        assertEquals(3, r.changes());
        Map<String, Set<String>> sent = FakeDiscordApi.synced.get(0);
        assertEquals(Set.of("forgeron"), sent.get("111111111111111111"));
        assertEquals(Set.of("forgeron"), sent.get("222222222222222222"));
        assertEquals(Set.of("taverne"), sent.get("333333333333333333"));
        assertEquals(Set.of(), sent.get("444444444444444444"));
        eco.employees.fire(alice, forge, bob.id()).join();
        links.syncJobs();
        assertEquals(Set.of(), FakeDiscordApi.synced.get(1).get("222222222222222222"));
        eco.companies.setEmblem(admin, forge, "BAN").join();
        links.syncJobs();
        assertEquals(Set.of(), FakeDiscordApi.synced.get(2).get("111111111111111111"));
    }

    @Test
    void nothingIsSentWhenRolesAreOffTheBotIsDownOrDiscordIsAbsent() {
        DiscordLinks links = new DiscordLinks(eco, fake());
        FakeDiscordApi.rolesOn = false;
        assertTrue(links.syncJobs().skipped());
        FakeDiscordApi.rolesOn = true; FakeDiscordApi.ready = false;
        assertTrue(links.syncJobs().skipped());
        assertTrue(FakeDiscordApi.synced.isEmpty());
        DiscordLinks absent = new DiscordLinks(eco, DiscordApiFacade.reflective("fr.citizen.discord.api.PasInstalle"));
        assertFalse(absent.available());
        assertTrue(absent.syncJobs().skipped());
        assertTrue(absent.playerOfDiscord("111111111111111111").isEmpty());
    }

    @Test
    void discordAccountsMapToPlayersByTheirPseudoOnly() {
        DiscordLinks links = new DiscordLinks(eco, fake());
        assertEquals(Optional.of(alice.id()), links.playerOfDiscord("111111111111111111"));
        assertEquals(Optional.of(gamer.id()), links.playerOfDiscord("333333333333333333"));
        assertEquals(Optional.empty(), links.playerOfDiscord("444444444444444444"));
        assertEquals(Optional.empty(), links.playerOfDiscord("999999999999999999"));
    }
}
