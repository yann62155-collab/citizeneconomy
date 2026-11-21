package fr.citizen.economy.web;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.Economy;
import fr.citizen.economy.ui.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DiscordLoginTest {
    static final class NoCash implements CashPlatform {
        public <T> T onServer(Supplier<T> t) { return t.get(); }
        public long[] countCoins(UUID p) { return new long[4]; }
        public void removeCoins(UUID p, long[] c) { }
        public boolean giveCoins(UUID p, long[] c) { return false; }
    }

    static final String ALICE_DISCORD = "111111111111111111", CARL_DISCORD = "222222222222222222", NOBODY = "333333333333333333";

    @TempDir Path tmp;
    Database db;
    Economy eco;
    UiService ui;
    WebPanel web;
    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    long now = 1_700_000_000_000L;
    boolean discordOn = true;
    String discordUserForNextCode = ALICE_DISCORD;
    final List<String> posted = new ArrayList<>();
    final Actor admin = new Actor(UUID.randomUUID(), "Roi", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);
    final Actor carl = new Actor(UUID.randomUUID(), "Carl", false);
    long forge;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        eco = new Economy(db, s -> { });
        ui = new UiService(eco, new NoCash(), () -> now += 1500);
        for (Actor a : List.of(admin, alice, bob, carl)) eco.players.touch(a).join();
        ui.handle(alice, "req.submit", new LinkedHashMap<>(Map.of("f_name", "Forge Royale", "f_desc", "Entreprise de test", "f_sector", "Forge", "f_emblem", "FOR", "_v", "create")));
        long rid = eco.companies.pendingRequests(admin).join().get(0).id();
        ui.handle(admin, "adm.req.accept", new LinkedHashMap<>(Map.of("r", Long.toString(rid), "f_note", "ok", "_v", "adm_requests")));
        forge = eco.companies.companiesOf(alice.id()).join().get(0).id();
        eco.employees.hire(alice, forge, bob.id(), bob.name(), 0).join();
        WebSettings st = new WebSettings() {
            public boolean enabled() { return true; }
            public int port() { return 0; }
            public String bindAddress() { return "127.0.0.1"; }
            public String councilPassword() { return "2007"; }
            public boolean discordEnabled() { return discordOn; }
            public String discordClientId() { return "999"; }
            public String discordClientSecret() { return "secret"; }
            public String discordRedirectUri() { return "http://serveur.test/auth/discord/callback"; }
        };
        web = new WebPanel(ui, eco, st, new LoginCodes(() -> now), Executors.newFixedThreadPool(2), () -> now, s -> { });
        DiscordOAuth.Http fake = new DiscordOAuth.Http() {
            public String postForm(String url, Map<String, String> form) throws IOException {
                posted.add(url + " " + form.get("code") + " " + form.get("client_secret"));
                if ("bad".equals(form.get("code"))) throw new IOException("refus");
                return "{\"access_token\":\"tok-" + form.get("code") + "\"}";
            }
            public String get(String url, String bearer) { return "{\"id\":\"" + discordUserForNextCode + "\"}"; }
        };
        Map<String, UUID> links = Map.of(ALICE_DISCORD, alice.id(), CARL_DISCORD, carl.id());
        web.setDiscord(new DiscordOAuth(st, fake), id -> Optional.ofNullable(links.get(id)));
        web.start();
    }

    @AfterEach
    void tearDown() { web.stop(); db.close(); }

    String url(String path) { return "http://127.0.0.1:" + web.port() + path; }

    HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String json, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("X-CE", "1");
        if (cookie != null) b.header("Cookie", "ce_session=" + cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String setCookie(HttpResponse<?> r, String name) {
        for (String c : r.headers().allValues("Set-Cookie")) if (c.startsWith(name + "=") && !c.startsWith(name + "=;")) return c.substring(name.length() + 1, c.indexOf(';'));
        return null;
    }

    /** Fait toute la boucle : /auth/discord puis le retour de Discord avec le bon etat. */
    HttpResponse<String> loginWithDiscord(String code) throws Exception {
        HttpResponse<String> start = get("/auth/discord", null);
        assertEquals(302, start.statusCode());
        String loc = start.headers().firstValue("Location").orElseThrow();
        assertTrue(loc.startsWith("https://discord.com/oauth2/authorize?client_id=999") && loc.contains("scope=identify") && loc.contains("redirect_uri=http%3A%2F%2Fserveur.test"), loc);
        String state = setCookie(start, "ce_oauth");
        assertNotNull(state);
        assertTrue(loc.contains("state=" + state));
        return get("/auth/discord/callback?code=" + code + "&state=" + state, "ce_oauth=" + state);
    }

    @Test
    void linkedPlayerWithACompanyIsLoggedInAutomatically() throws Exception {
        discordUserForNextCode = ALICE_DISCORD;
        HttpResponse<String> cb = loginWithDiscord("abc");
        assertEquals(302, cb.statusCode(), cb.body());
        assertEquals("/", cb.headers().firstValue("Location").orElseThrow());
        String session = setCookie(cb, "ce_session");
        assertNotNull(session);
        assertTrue(cb.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("ce_session=") && c.contains("HttpOnly") && c.contains("SameSite=Strict")));
        HttpResponse<String> who = post("/api/session", "{}", session);
        assertEquals(200, who.statusCode());
        assertTrue(who.body().contains("Alice") && who.body().contains("\"council\":false"), who.body());
        assertTrue(posted.get(0).startsWith(DiscordOAuth.TOKEN + " abc secret"));
        // la session Discord n'ouvre jamais l'administration
        assertTrue(post("/api/ui", "{\"action\":\"open\",\"args\":{\"to\":\"admin\",\"_v\":\"companies\"}}", session).statusCode() == 403);
    }

    @Test
    void forgedOrReusedStateIsRefused() throws Exception {
        HttpResponse<String> start = get("/auth/discord", null);
        String state = setCookie(start, "ce_oauth");
        // pas de cookie : refuse
        assertEquals(400, get("/auth/discord/callback?code=abc&state=" + state, null).statusCode());
        // le meme etat ne sert qu'une fois (le premier essai l'a consomme)
        assertEquals(400, get("/auth/discord/callback?code=abc&state=" + state, "ce_oauth=" + state).statusCode());
        // etat invente
        assertEquals(400, get("/auth/discord/callback?code=abc&state=deadbeef", "ce_oauth=deadbeef").statusCode());
        assertTrue(posted.isEmpty(), "aucun echange de code ne doit avoir lieu : " + posted);
        // etat valide mais cookie d'un autre navigateur
        String s2 = setCookie(get("/auth/discord", null), "ce_oauth");
        assertEquals(400, get("/auth/discord/callback?code=abc&state=" + s2, "ce_oauth=autre").statusCode());
    }

    @Test
    void unlinkedOrWorklessAccountsAndDiscordErrorsAreRefused() throws Exception {
        discordUserForNextCode = NOBODY;                                  // aucun lien /pseudo
        HttpResponse<String> r1 = loginWithDiscord("abc");
        assertEquals(403, r1.statusCode());
        assertTrue(r1.body().contains("/pseudo"));
        assertNull(setCookie(r1, "ce_session"));
        discordUserForNextCode = CARL_DISCORD;                            // lie, mais sans entreprise
        HttpResponse<String> r2 = loginWithDiscord("abc");
        assertEquals(403, r2.statusCode());
        assertTrue(r2.body().contains("Carl"));
        assertNull(setCookie(r2, "ce_session"));
        assertEquals(502, loginWithDiscord("bad").statusCode());         // Discord refuse le code
    }

    @Test
    void discordLoginCanBeSwitchedOff() throws Exception {
        assertTrue(post("/api/info", "{}", null).body().contains("\"discord\":true"));
        discordOn = false;
        assertTrue(post("/api/info", "{}", null).body().contains("\"discord\":false"));
        assertEquals(404, get("/auth/discord", null).statusCode());
        assertEquals(404, get("/auth/discord/callback?code=a&state=b", null).statusCode());
    }

    @Test
    void employeesNeedWorkRightsToUseThePortal() throws Exception {
        assertTrue(web.canUsePortal(alice.id()));                          // patron
        assertFalse(web.canUsePortal(bob.id()));                           // simple employe : annonces seulement
        assertFalse(web.canUsePortal(carl.id()));                          // aucune entreprise
        eco.employees.setPermissions(alice, forge, bob.id(), Permission.CASHIER.bit()).join();
        assertTrue(web.canUsePortal(bob.id()));                            // caissier
        String code = web.codes().issue(bob.id(), "Bob");
        HttpResponse<String> r = post("/api/login", "{\"code\":\"" + code + "\"}", null);
        assertEquals(200, r.statusCode(), r.body());
        String tok = setCookie(r, "ce_session");
        // le caissier ouvre la caisse, mais pas la comptabilite
        String pos = post("/api/ui", "{\"action\":\"open\",\"args\":{\"to\":\"pos\",\"id\":\"" + forge + "\",\"_v\":\"company\"}}", tok).body();
        assertTrue(pos.contains("Caisse"), pos);
        String acct = post("/api/ui", "{\"action\":\"open\",\"args\":{\"to\":\"acct\",\"id\":\"" + forge + "\",\"_v\":\"company\"}}", tok).body();
        assertTrue(acct.contains("\"kind\":\"err\"") || acct.contains("Permission"), acct);
    }
}
