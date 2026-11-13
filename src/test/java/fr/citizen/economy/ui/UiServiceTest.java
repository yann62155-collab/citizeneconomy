package fr.citizen.economy.ui;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class UiServiceTest {
    /** Inventaire en memoire. */
    static final class FakeCash implements CashPlatform {
        final Map<UUID, long[]> inv = new HashMap<>();
        boolean online = true;
        public <T> T onServer(Supplier<T> t) { return t.get(); }
        public long[] countCoins(UUID p) { return inv.computeIfAbsent(p, k -> new long[4]).clone(); }
        public void removeCoins(UUID p, long[] c) { long[] h = inv.computeIfAbsent(p, k -> new long[4]); for (int i = 0; i < 4; i++) h[i] -= c[i]; }
        public boolean giveCoins(UUID p, long[] c) { if (!online) return false; long[] h = inv.computeIfAbsent(p, k -> new long[4]); for (int i = 0; i < 4; i++) h[i] += c[i]; return true; }
        long value(UUID p) { return Coins.total(inv.computeIfAbsent(p, k -> new long[4])); }
        void set(UUID p, long amount) { inv.put(p, Coins.breakdown(amount)); }
    }

    @TempDir Path tmp;
    Database db;
    Economy eco;
    FakeCash cash;
    UiService ui;
    long now = 1_700_000_000_000L;
    final Actor admin = new Actor(UUID.randomUUID(), "Roi", true);
    final Actor alice = new Actor(UUID.randomUUID(), "Alice", false);
    final Actor bob = new Actor(UUID.randomUUID(), "Bob", false);

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("t.db"), tmp.resolve("b"));
        db.open();
        eco = new Economy(db, s -> { });
        cash = new FakeCash();
        ui = new UiService(eco, cash, () -> now);
        for (Actor a : List.of(admin, alice, bob)) eco.players.touch(a).join();
    }

    @AfterEach
    void tearDown() { db.close(); }

    Map<String, String> m(String... kv) {
        Map<String, String> r = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) r.put(kv[i], kv[i + 1]);
        return r;
    }

    View act(Actor a, String action, String... kv) {
        now += 1500; // evite la limitation de debit
        return ui.handle(a, action, m(kv));
    }

    long bal(Actor a) { return eco.bank.personalBalance(a).join(); }

    @Test
    void depositWithChangeThenReplayIsRefunded() {
        cash.set(alice.id(), 100); // une piece d'or
        View v = act(alice, "bank.deposit", "f_amount", "30", "_n", "n1", "_v", "bank");
        assertEquals("ok", v.kind(), v.message());
        assertEquals(30, bal(alice));
        assertEquals(70, cash.value(alice.id()));
        // double clic : meme jeton -> aucune deuxieme operation, pieces conservees
        View v2 = act(alice, "bank.deposit", "f_amount", "30", "_n", "n1", "_v", "bank");
        assertEquals(30, bal(alice));
        assertEquals(70, cash.value(alice.id()));
        assertTrue(v2.message().contains("déjà"), v2.message());
    }

    @Test
    void depositWithoutEnoughCoinsFails() {
        cash.set(alice.id(), 5);
        View v = act(alice, "bank.deposit", "f_amount", "30", "_n", "x", "_v", "bank");
        assertEquals("err", v.kind());
        assertEquals(0, bal(alice));
        assertEquals(5, cash.value(alice.id()));
    }

    @Test
    void invalidAmountsRejected() {
        cash.set(alice.id(), 500);
        for (String bad : List.of("0", "-5", "abc", "1.5", "", "99999999999999999999")) {
            View v = act(alice, "bank.deposit", "f_amount", bad, "_v", "bank");
            assertEquals("err", v.kind(), bad);
        }
        assertEquals(0, bal(alice));
        assertEquals(500, cash.value(alice.id()));
    }

    @Test
    void withdrawGivesCoinsAndCompensatesWhenOffline() {
        cash.set(alice.id(), 250);
        act(alice, "bank.deposit", "f_amount", "250", "_n", "a", "_v", "bank");
        assertEquals(250, bal(alice));
        View v = act(alice, "bank.withdraw", "f_amount", "120", "_n", "b", "_v", "bank");
        assertEquals("ok", v.kind(), v.message());
        assertEquals(130, bal(alice));
        assertEquals(120, cash.value(alice.id()));
        cash.online = false;
        View v2 = act(alice, "bank.withdraw", "f_amount", "50", "_n", "c", "_v", "bank");
        assertEquals("err", v2.kind());
        assertEquals(130, bal(alice)); // compense
    }

    @Test
    void transferBetweenPlayers() {
        cash.set(alice.id(), 100);
        act(alice, "bank.deposit", "f_amount", "100", "_n", "a", "_v", "bank");
        View v = act(alice, "bank.transfer", "f_amount", "40", "f_target", "bob", "f_desc", "loyer", "_n", "t", "_v", "bank");
        assertEquals("ok", v.kind(), v.message());
        assertEquals(60, bal(alice));
        assertEquals(40, bal(bob));
        assertEquals("err", act(alice, "bank.transfer", "f_amount", "10", "f_target", "inconnu", "_v", "bank").kind());
    }

    @Test
    void adminOnlyActionsAndViewsAreForbiddenToPlayers() {
        assertEquals("err", act(alice, "adm.spend", "f_amount", "10", "f_target", "alice", "f_reason", "cadeau", "_v", "treasury").kind());
        assertEquals("err", act(alice, "open", "to", "admin").kind());
        assertEquals("err", act(alice, "open", "to", "treasury").kind());
        assertEquals("err", act(alice, "adm.revenue", "f_amount", "10", "f_reason", "amende", "_v", "main").kind());
        assertEquals(0, bal(alice));
        assertEquals(0, eco.treasury.balance(admin).join());
    }

    @Test
    void royalRevenueAndExpense() {
        cash.set(admin.id(), 300);
        View v = act(admin, "adm.revenue", "f_amount", "200", "f_reason", "amende", "_n", "r1", "_v", "treasury");
        assertEquals("ok", v.kind(), v.message());
        assertEquals(200, eco.treasury.balance(admin).join());
        assertEquals(100, cash.value(admin.id()));
        v = act(admin, "adm.spend", "to", "target", "f_amount", "50", "f_target", "alice", "f_reason", "récompense", "_n", "s1", "_v", "treasury");
        assertEquals("ok", v.kind(), v.message());
        assertEquals(50, bal(alice));
        assertEquals(150, eco.treasury.balance(admin).join());
        // sans motif : refuse
        assertEquals("err", act(admin, "adm.spend", "to", "target", "f_amount", "5", "f_target", "alice", "f_reason", "", "_v", "treasury").kind());
        // retrait en especes
        v = act(admin, "adm.spend", "to", "cash", "f_amount", "100", "f_reason", "solde de troupes", "_n", "s2", "_v", "treasury");
        assertEquals("ok", v.kind(), v.message());
        assertEquals(50, eco.treasury.balance(admin).join());
        assertEquals(200, cash.value(admin.id()));
    }

    @Test
    void companyCreationFlowThroughUi() {
        View v = act(alice, "req.submit", "f_name", "Forge Royale", "f_desc", "Armes et armures pour le royaume", "f_sector", "Forge", "f_emblem", "marteau", "_v", "create");
        assertEquals("ok", v.kind(), v.message());
        v = act(admin, "open", "to", "adm_requests");
        assertTrue(text(v).contains("Forge Royale"));
        long rid = eco.companies.pendingRequests(admin).join().get(0).id();
        assertEquals("ok", act(admin, "adm.req.accept", "r", Long.toString(rid), "f_note", "Bienvenue", "_v", "adm_requests").kind());
        List<Company> mine = eco.companies.companiesOf(alice.id()).join();
        assertEquals(1, mine.size());
        long cid = mine.get(0).id();
        String id = Long.toString(cid);
        // recrutement par le proprietaire, puis tentative d'un simple employe de recruter
        eco.players.touch(bob).join();
        assertEquals("ok", act(alice, "emp.hire", "id", id, "f_player", "Bob", "f_salary", "30", "_v", "employees").kind());
        assertEquals("err", act(bob, "emp.hire", "id", id, "f_player", "Alice", "f_salary", "999", "_v", "employees").kind());
        assertEquals("err", act(bob, "emp.salary", "id", id, "p", bob.id().toString(), "f_salary", "9999", "_v", "employees").kind());
        // caisse
        cash.set(alice.id(), 1000);
        act(alice, "bank.deposit", "f_amount", "1000", "_n", "d", "_v", "bank");
        assertEquals("ok", act(alice, "cbank.deposit", "id", id, "f_amount", "400", "_n", "e", "_v", "cbank").kind());
        assertEquals(400, eco.bank.businessBalance(alice, cid).join());
        assertEquals("err", act(bob, "cbank.withdraw", "id", id, "f_amount", "100", "_n", "f", "_v", "cbank").kind());
        assertEquals(400, eco.bank.businessBalance(alice, cid).join());
        // annonce + facture
        assertEquals("ok", act(alice, "ann.publish", "id", id, "vis", "PUBLIC", "f_title", "Ouverture", "f_body", "La forge ouvre ses portes aujourd'hui", "f_cat", "Information", "_v", "cboard").kind());
        assertTrue(text(act(bob, "open", "to", "board")).contains("Ouverture"));
        assertEquals("ok", act(alice, "inv.create", "id", id, "kind", "player", "f_target", "Bob", "f_desc", "Epee", "f_amount", "25", "_v", "cinvoices").kind());
        cash.set(bob.id(), 100);
        act(bob, "bank.deposit", "f_amount", "100", "_n", "g", "_v", "bank");
        long iid = eco.invoices.myInvoices(bob, null, 10).join().get(0).id();
        assertEquals("ok", act(bob, "inv.pay", "i", Long.toString(iid), "_v", "invoices").kind());
        assertEquals(75, bal(bob));
        assertEquals(425, eco.bank.businessBalance(alice, cid).join());
        assertEquals("err", act(bob, "inv.pay", "i", Long.toString(iid), "_v", "invoices").kind()); // pas deux fois
        assertEquals(75, bal(bob));
    }

    @Test
    void everyScreenRendersForPlayerAndAdmin() {
        act(alice, "req.submit", "f_name", "Auberge", "f_desc", "Repas et chambres pour voyageurs", "f_sector", "Taverne", "f_emblem", "", "_v", "create");
        long rid = eco.companies.pendingRequests(admin).join().get(0).id();
        act(admin, "adm.req.accept", "r", Long.toString(rid), "f_note", "ok", "_v", "adm_requests");
        String id = Long.toString(eco.companies.companiesOf(alice.id()).join().get(0).id());
        for (String s : List.of("main", "bank", "create", "companies", "board", "invoices", "tax", "notifs"))
            assertNotEquals("err", act(alice, "open", "to", s).kind(), s);
        for (String s : List.of("company", "employees", "perms", "salaryhist", "cbank", "cboard", "cinvoices", "ctax")) {
            View v = act(alice, "open", "to", s, "id", id, "p", alice.id().toString());
            assertNotEquals("err", v.kind(), s + " : " + v.message());
            // les vues doivent survivre au codec reseau
            assertEquals(v.id(), ViewCodec.fromJson(ViewCodec.toJson(v)).id());
        }
        for (String s : List.of("admin", "treasury", "adm_companies", "adm_requests", "adm_players", "adm_tax", "adm_tx", "adm_taxhist", "adm_ann", "adm_audit", "adm_settings", "adm_limits")) {
            View v = act(admin, "open", "to", s);
            assertNotEquals("err", v.kind(), s + " : " + v.message());
            assertEquals(s, v.id());
        }
        for (String role : List.of("OWNER", "MANAGER", "EMPLOYEE")) assertNotEquals("err", act(admin, "open", "to", "adm_members", "role", role).kind());
        assertEquals("err", act(admin, "open", "to", "nimporte_quoi").kind());
    }

    @Test
    void adminTaxConfigurationAndExemption() {
        assertEquals("ok", act(admin, "adm.tax.rate", "kind", "BUSINESS_INCOME", "f_rate", "12,5", "_v", "adm_tax").kind());
        TaxRate r = eco.tax.latestRates(admin).join().stream().filter(x -> x.kind() == TaxKind.BUSINESS_INCOME).findFirst().orElseThrow();
        assertEquals(1250, r.rateBp());
        assertEquals("err", act(admin, "adm.tax.rate", "kind", "BUSINESS_INCOME", "f_rate", "150", "_v", "adm_tax").kind());
        assertEquals("err", act(admin, "adm.tax.rate", "kind", "BUSINESS_INCOME", "f_rate", "abc", "_v", "adm_tax").kind());
        assertEquals("ok", act(admin, "adm.tax.exempt", "who", "player", "f_subject", "Alice", "_v", "adm_tax").kind());
        assertEquals("ok", act(admin, "adm.limits", "f_l_title", "50", "f_l_body", "500", "f_l_hour", "3", "f_l_cool", "10", "f_l_active", "20", "_v", "adm_limits").kind());
        assertEquals(3, eco.announcements.limits().join().perHour());
        assertEquals("ok", act(admin, "adm.verify", "_v", "adm_settings").kind());
    }

    @Test
    void adminWhoOwnsACompanyCanIssueInvoicesAndPublish() {
        act(alice, "req.submit", "f_name", "Le tavernier", "f_desc", "Taverne du village", "f_sector", "Taverne", "f_emblem", "", "_v", "create");
        act(admin, "adm.req.accept", "r", Long.toString(eco.companies.pendingRequests(admin).join().get(0).id()), "f_note", "ok", "_v", "adm_requests");
        String id = Long.toString(eco.companies.companiesOf(alice.id()).join().get(0).id());
        Actor aliceOp = new Actor(alice.id(), "Alice", true); // meme joueur, devenu operateur
        View v = act(aliceOp, "open", "to", "cinvoices", "id", id);
        assertTrue(v.fields().stream().anyMatch(f -> f.id().equals("f_amount")), "le formulaire de facture doit etre visible");
        assertEquals("ok", act(aliceOp, "inv.create", "id", id, "kind", "player", "f_target", "Bob", "f_desc", "Biere", "f_amount", "12", "_v", "cinvoices").kind());
        assertEquals("ok", act(aliceOp, "ann.publish", "id", id, "vis", "PUBLIC", "f_title", "Ouvert", "f_body", "La taverne est ouverte a tous", "f_cat", "Information", "_v", "cboard").kind());
        // un admin qui n'est PAS membre n'emet toujours rien au nom de l'entreprise
        assertEquals("err", act(admin, "inv.create", "id", id, "kind", "player", "f_target", "Bob", "f_desc", "x", "f_amount", "5", "_v", "cinvoices").kind());
    }

    @Test
    void ownerCanSetOwnSalaryAndIsPaidByPayroll() {
        act(alice, "req.submit", "f_name", "Le tavernier", "f_desc", "Taverne du village", "f_sector", "Taverne", "f_emblem", "", "_v", "create");
        act(admin, "adm.req.accept", "r", Long.toString(eco.companies.pendingRequests(admin).join().get(0).id()), "f_note", "ok", "_v", "adm_requests");
        long cid = eco.companies.companiesOf(alice.id()).join().get(0).id();
        String id = Long.toString(cid);
        View v = act(alice, "open", "to", "employees", "id", id);
        assertTrue(v.lines().stream().anyMatch(l -> l.btns().stream().anyMatch(b -> b.action().equals("emp.salary"))), "bouton Salaire sur la ligne du patron");
        assertEquals("ok", act(alice, "emp.salary", "id", id, "p", alice.id().toString(), "f_salary", "150", "_v", "employees").kind());
        cash.set(alice.id(), 1000);
        act(alice, "bank.deposit", "f_amount", "1000", "_n", "d", "_v", "bank");
        act(alice, "cbank.deposit", "id", id, "f_amount", "500", "_n", "e", "_v", "cbank");
        eco.payroll.runCompany(cid, 1L).join();
        assertEquals(650, bal(alice));
        assertEquals(350, eco.bank.businessBalance(alice, cid).join());
    }

    @Test
    void invoicesToPlayersUseTheHibouNumberWhenHibouIsInstalled() {
        Map<Integer, UUID> numbers = Map.of(7, bob.id(), 12, alice.id());
        UiService withHibou = new UiService(eco, cash, () -> now, new HibouDirectory() {
            public boolean available() { return true; }
            public Optional<UUID> findByNumber(int n) { return Optional.ofNullable(numbers.get(n)); }
            public int numberOf(UUID p) { return numbers.entrySet().stream().filter(e -> e.getValue().equals(p)).map(Map.Entry::getKey).findFirst().orElse(0); }
        });
        act(alice, "req.submit", "f_name", "Le tavernier", "f_desc", "Taverne du village", "f_sector", "Taverne", "f_emblem", "", "_v", "create");
        act(admin, "adm.req.accept", "r", Long.toString(eco.companies.pendingRequests(admin).join().get(0).id()), "f_note", "ok", "_v", "adm_requests");
        String id = Long.toString(eco.companies.companiesOf(alice.id()).join().get(0).id());
        java.util.function.BiFunction<String, String, View> invoice = (target, amount) -> {
            now += 1500;
            return withHibou.handle(alice, "inv.create", m("id", id, "kind", "player", "f_target", target, "f_desc", "Biere", "f_amount", amount, "_v", "cinvoices"));
        };
        assertEquals("err", invoice.apply("Bob", "5").kind());     // le pseudo n'est plus accepte
        assertEquals("err", invoice.apply("99", "5").kind());      // numero inconnu
        assertEquals("ok", invoice.apply("0007", "5").kind());     // numero de hibou
        assertEquals("ok", invoice.apply("n°7", "6").kind());
        List<Invoice> bobs = eco.invoices.myInvoices(bob, null, 10).join();
        assertEquals(2, bobs.size());
        now += 1500;
        View v = withHibou.handle(alice, "open", m("to", "cinvoices", "id", id));
        assertTrue(v.lines().stream().anyMatch(l -> l.text().contains("hibou n°0007")), "le numero doit s'afficher");
        // sans le mod Hibou, le pseudo reste accepte
        View plain = act(alice, "inv.create", "id", id, "kind", "player", "f_target", "Bob", "f_desc", "Verre", "f_amount", "3", "_v", "cinvoices");
        assertEquals("ok", plain.kind(), plain.message());
    }

    @Test
    void publicAnnouncementIsBroadcastWithCompanyNameButInternalOneIsNot() {
        List<String> chat = new ArrayList<>();
        UiService svc = new UiService(eco, cash, () -> now, HibouDirectory.NONE, (company, category, title, body) -> chat.add(company + "|" + category + "|" + title + "|" + body));
        act(alice, "req.submit", "f_name", "Le tavernier", "f_desc", "Taverne du village", "f_sector", "Taverne", "f_emblem", "", "_v", "create");
        act(admin, "adm.req.accept", "r", Long.toString(eco.companies.pendingRequests(admin).join().get(0).id()), "f_note", "ok", "_v", "adm_requests");
        String id = Long.toString(eco.companies.companiesOf(alice.id()).join().get(0).id());
        now += 1500;
        View v = svc.handle(alice, "ann.publish", m("id", id, "vis", "PUBLIC", "f_title", "Soiree musicale", "f_body", "Ce soir, musique et bonne biere", "f_cat", "Evenement", "_v", "cboard"));
        assertEquals("ok", v.kind(), v.message());
        assertEquals(List.of("Le tavernier|Événement|Soiree musicale|Ce soir, musique et bonne biere"), chat);
        now += 1500;
        svc.handle(alice, "ann.publish", m("id", id, "vis", "INTERNAL", "f_title", "Reunion du personnel", "f_body", "Reunion demain matin a la taverne", "f_cat", "Information", "_v", "cboard"));
        assertEquals(1, chat.size()); // l'annonce interne n'est jamais diffusee a tous
        now += 1500;
        svc.handle(alice, "ann.publish", m("id", id, "vis", "PUBLIC", "f_title", "x", "f_body", "trop court", "f_cat", "Information", "_v", "cboard"));
        assertEquals(1, chat.size()); // une publication refusee ne diffuse rien
    }

    @Test
    void rateLimitBlocksBursts() {
        int errors = 0;
        for (int i = 0; i < 12; i++) if (ui.handle(alice, "open", m("to", "main")).kind().equals("err")) errors++;
        assertTrue(errors >= 3, "attendu au moins 3 refus, obtenu " + errors);
        now += 2000;
        assertNotEquals("err", ui.handle(alice, "open", m("to", "main")).kind());
    }

    @Test
    void forgedClientDataCannotEscalate() {
        // un client malveillant envoie des cles internes arbitraires : elles ne changent pas l'identite
        View v = act(alice, "adm.spend", "f_amount", "10", "to", "target", "f_target", "alice", "f_reason", "x", "admin", "true", "actor", admin.id().toString(), "_v", "treasury");
        assertEquals("err", v.kind());
        assertEquals("err", act(alice, "action.inconnue", "_v", "main").kind());
        assertEquals("err", act(alice, "emp.fire", "id", "9999", "p", alice.id().toString(), "_v", "main").kind());
    }

    private static String text(View v) {
        StringBuilder b = new StringBuilder(v.title());
        for (View.Line l : v.lines()) b.append('\n').append(l.text());
        return b.toString();
    }

    @Test
    void companiesAreFoundAndPaidByMatricule() {
        act(alice, "req.submit", "f_name", "Forge Royale", "f_desc", "Armes et armures", "f_sector", "Forge", "f_emblem", "", "_v", "create");
        act(bob, "req.submit", "f_name", "Taverne du Lion", "f_desc", "Biere et repas", "f_sector", "Taverne", "f_emblem", "", "_v", "create");
        for (CompanyRequest r : eco.companies.pendingRequests(admin).join())
            act(admin, "adm.req.accept", "r", Long.toString(r.id()), "f_note", "ok", "_v", "adm_requests");
        Company forge = eco.companies.companiesOf(alice.id()).join().get(0);
        Company taverne = eco.companies.companiesOf(bob.id()).join().get(0);
        assertNotNull(forge.matricule());
        assertNotEquals(forge.matricule(), taverne.matricule());
        assertTrue(forge.matricule().matches("E-\\d{4}"));
        assertTrue(text(act(alice, "open", "to", "company", "id", Long.toString(forge.id()))).contains(forge.matricule()));
        // recherche : matricule complet, minuscules, chiffres seuls
        assertEquals(taverne.id(), eco.companies.findActiveByName(taverne.matricule()).join().orElseThrow().id());
        assertEquals(taverne.id(), eco.companies.findActiveByName(taverne.matricule().toLowerCase()).join().orElseThrow().id());
        assertEquals(taverne.id(), eco.companies.findActiveByName(Long.toString(taverne.id())).join().orElseThrow().id());
        assertTrue(eco.companies.findActiveByName("E-9999").join().isEmpty());
        // facture d'entreprise a entreprise, designee par matricule
        assertEquals("ok", act(alice, "inv.create", "id", Long.toString(forge.id()), "kind", "company", "f_target", taverne.matricule(),
                "f_desc", "Fer", "f_amount", "20", "_v", "cinvoices").kind());
        assertEquals(1, eco.invoices.receivedByCompany(bob, taverne.id(), null, 10).join().size());
    }
}
