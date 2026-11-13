package fr.citizen.economy.web;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.Economy;
import fr.citizen.economy.ui.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class WebPanelTest {
    static final class NoCash implements CashPlatform {
        public <T> T onServer(Supplier<T> t) { return t.get(); }
        public long[] countCoins(UUID p) { return new long[4]; }
        public void removeCoins(UUID p, long[] c) { }
        public boolean giveCoins(UUID p, long[] c) { return false; }
    }

    @TempDir Path tmp;
    Database db;
    Economy eco;
    UiService ui;
    WebPanel web;
    HttpClient http = HttpClient.newHttpClient();
    long now = 1_700_000_000_000L;
    String password = "2007";
    final Actor admin = new Actor(UUID.randomUUID(), "Roi", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);
    final Actor carl = new Actor(UUID.randomUUID(), "Carl", false);
    long forge, taverne;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        eco = new Economy(db, s -> { });
        ui = new UiService(eco, new NoCash(), () -> now += 1500);
        for (Actor a : List.of(admin, alice, bob, carl)) eco.players.touch(a).join();
        forge = company(alice, "Forge Royale", "Forge");
        taverne = company(bob, "Taverne du Lion", "Taverne");
        WebSettings st = new WebSettings() {
            public boolean enabled() { return true; }
            public int port() { return 0; }
            public String bindAddress() { return "127.0.0.1"; }
            public String councilPassword() { return password; }
        };
        web = new WebPanel(ui, eco, st, new LoginCodes(() -> now), Executors.newFixedThreadPool(2), () -> now, s -> { });
        web.start();
    }

    @AfterEach
    void tearDown() { web.stop(); db.close(); }

    long company(Actor owner, String name, String sector) {
        ui.handle(owner, "req.submit", new LinkedHashMap<>(Map.of("f_name", name, "f_desc", "Entreprise de test", "f_sector", sector, "f_emblem", "", "_v", "create")));
        long rid = eco.companies.pendingRequests(admin).join().get(0).id();
        ui.handle(admin, "adm.req.accept", new LinkedHashMap<>(Map.of("r", Long.toString(rid), "f_note", "ok", "_v", "adm_requests")));
        return eco.companies.companiesOf(owner.id()).join().get(0).id();
    }

    record Res(int status, String body, String cookie) { }

    Res post(String path, String json, String cookie, boolean header) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json");
        if (header) b.header("X-CE", "1");
        if (cookie != null) b.header("Cookie", "ce_session=" + cookie);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String set = r.headers().firstValue("Set-Cookie").orElse(null);
        String tok = null;
        if (set != null && set.startsWith("ce_session=") && !set.startsWith("ce_session=;")) tok = set.substring(11, set.indexOf(';'));
        return new Res(r.statusCode(), r.body(), tok);
    }

    String councilToken() throws Exception {
        Res r = post("/api/login", "{\"password\":\"2007\"}", null, true);
        assertEquals(200, r.status(), r.body());
        assertNotNull(r.cookie());
        return r.cookie();
    }

    String ui(String tok, String action, String args) throws Exception {
        Res r = post("/api/ui", "{\"action\":\"" + action + "\",\"args\":" + args + "}", tok, true);
        return r.status() + "|" + r.body();
    }

    @Test
    void councilSeesEveryCompanyAndManagesThemAsOwner() throws Exception {
        assertEquals(401, post("/api/login", "{\"password\":\"mauvais\"}", null, true).status());
        String tok = councilToken();
        String list = ui(tok, "open", "{\"to\":\"companies\"}");
        assertTrue(list.startsWith("200|"), list);
        assertTrue(list.contains("Forge Royale") && list.contains("Taverne du Lion"), list);
        // recrutement + salaire dans une entreprise qui n'est pas la sienne
        eco.players.touch(carl).join();
        String hire = ui(tok, "emp.hire", "{\"id\":\"" + forge + "\",\"f_player\":\"Carl\",\"f_salary\":\"30\",\"_v\":\"employees\"}");
        assertTrue(hire.startsWith("200|") && hire.contains("\"kind\":\"ok\""), hire);
        String sal = ui(tok, "emp.salary", "{\"id\":\"" + forge + "\",\"p\":\"" + carl.id() + "\",\"f_salary\":\"45\",\"_v\":\"employees\"}");
        assertTrue(sal.contains("\"kind\":\"ok\""), sal);
        assertEquals(45, eco.employees.members(Actor.council(), forge).join().stream().filter(m -> m.player().equals(carl.id())).findFirst().orElseThrow().salary());
        // le Conseil peut aussi fixer le salaire du proprietaire et publier une annonce
        String own = ui(tok, "emp.salary", "{\"id\":\"" + forge + "\",\"p\":\"" + alice.id() + "\",\"f_salary\":\"20\",\"_v\":\"employees\"}");
        assertTrue(own.contains("\"kind\":\"ok\""), own);
        // et le bouton "Salaire" est bien propose sur la ligne du patron
        String emp = ui(tok, "open", "{\"to\":\"employees\",\"id\":\"" + forge + "\"}");
        assertTrue(emp.split("emp.salary", -1).length - 1 >= 2, emp); // patron + employe recrute
        String ann = ui(tok, "ann.publish", "{\"id\":\"" + taverne + "\",\"vis\":\"INTERNAL\",\"f_title\":\"Reunion\",\"f_body\":\"Reunion du conseil demain\",\"f_cat\":\"Information\",\"_v\":\"cboard\"}");
        assertTrue(ann.contains("\"kind\":\"ok\""), ann);
    }

    @Test
    void councilCannotReachAdminPersonalBankOrCoinActions() throws Exception {
        String tok = councilToken();
        assertTrue(ui(tok, "bank.deposit", "{\"f_amount\":\"5\"}").startsWith("403"));
        assertTrue(ui(tok, "adm.co.close", "{\"id\":\"" + forge + "\"}").startsWith("403"));
        assertTrue(ui(tok, "cbank.deposit", "{\"id\":\"" + forge + "\"}").startsWith("403"));
        assertTrue(ui(tok, "open", "{\"to\":\"admin\"}").startsWith("403"));
        assertTrue(ui(tok, "open", "{\"to\":\"bank\"}").startsWith("403"));
        // et les boutons interdits ne sont meme pas proposes
        String fin = ui(tok, "open", "{\"to\":\"cbank\",\"id\":\"" + forge + "\"}");
        assertFalse(fin.contains("cbank.deposit") || fin.contains("cbank.withdraw"), fin);
    }

    @Test
    void ownerLoginNeedsAOneTimeCodeAndAnOwnedCompany() throws Exception {
        String code = web.codes().issue(alice.id(), "Alice");
        Res r = post("/api/login", "{\"code\":\"" + code + "\"}", null, true);
        assertEquals(200, r.status(), r.body());
        String tok = r.cookie();
        // le code ne sert qu'une fois
        assertEquals(401, post("/api/login", "{\"code\":\"" + code + "\"}", null, true).status());
        // seulement ses propres entreprises
        String list = ui(tok, "open", "{\"to\":\"companies\"}");
        assertTrue(list.contains("Forge Royale") && !list.contains("Taverne du Lion"), list);
        // impossible d'agir sur l'entreprise d'un autre
        String other = ui(tok, "emp.hire", "{\"id\":\"" + taverne + "\",\"f_player\":\"Carl\",\"f_salary\":\"10\",\"_v\":\"employees\"}");
        assertFalse(other.contains("\"kind\":\"ok\""), other);
        assertTrue(eco.employees.members(Actor.council(), taverne).join().stream().noneMatch(m -> m.player().equals(carl.id())));
        // un joueur sans entreprise n'entre pas
        String codeCarl = web.codes().issue(carl.id(), "Carl");
        assertEquals(401, post("/api/login", "{\"code\":\"" + codeCarl + "\"}", null, true).status());
        // un code expire
        String old = web.codes().issue(bob.id(), "Bob");
        now += 6 * 60_000L;
        assertEquals(401, post("/api/login", "{\"code\":\"" + old + "\"}", null, true).status());
    }

    @Test
    void sessionsAndBruteForceAreProtected() throws Exception {
        assertEquals(401, post("/api/ui", "{\"action\":\"open\",\"args\":{\"to\":\"companies\"}}", null, true).status());
        assertEquals(403, post("/api/login", "{\"password\":\"2007\"}", null, false).status()); // en-tete anti-CSRF absent
        for (int i = 0; i < 5; i++) assertEquals(401, post("/api/login", "{\"password\":\"x" + i + "\"}", null, true).status());
        assertEquals(429, post("/api/login", "{\"password\":\"2007\"}", null, true).status()); // verrouille meme avec le bon mot de passe
        now += WebPanel.FAIL_WINDOW_MS + 1000;
        assertEquals(200, post("/api/login", "{\"password\":\"2007\"}", null, true).status());
        // mot de passe vide = acces Conseil ferme
        password = "";
        assertEquals(401, post("/api/login", "{\"password\":\"\"}", null, true).status());
    }
}
