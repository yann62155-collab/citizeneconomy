package fr.citizen.economy.ui;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.service.*;
import fr.citizen.economy.ui.View.Btn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Coeur de l'interface pilotee par le serveur : construit chaque ecran (View) et execute les actions des boutons.
 * Aucune donnee du client n'est crue : l'identite (Actor) vient du serveur, chaque action repasse par les services
 * metier qui revalident permissions, soldes et montants. Appeler depuis un thread de travail (appels bloquants).
 */
public final class UiService {
    private static final Set<String> KEEP = Set.of("id", "p", "a", "i", "d", "r", "mode", "role", "pr", "s", "per");
    private static final long DAY = 86_400_000L;
    private static final int LIST = 25;

    private final Economy eco;
    private final CashPlatform cash;
    private final LongSupplier clock;
    private final Map<UUID, ArrayDeque<Long>> recent = new ConcurrentHashMap<>();
    private volatile ZoneId zone = ZoneId.systemDefault();
    private volatile long zoneAt = 0;

    private final HibouDirectory hibou;
    private final Broadcaster broadcaster;

    public UiService(Economy eco, CashPlatform cash) { this(eco, cash, System::currentTimeMillis, HibouDirectory.NONE); }
    public UiService(Economy eco, CashPlatform cash, LongSupplier clock) { this(eco, cash, clock, HibouDirectory.NONE); }
    public UiService(Economy eco, CashPlatform cash, LongSupplier clock, HibouDirectory hibou) { this(eco, cash, clock, hibou, Broadcaster.NONE); }
    public UiService(Economy eco, CashPlatform cash, LongSupplier clock, HibouDirectory hibou, Broadcaster broadcaster) {
        this.eco = eco; this.cash = cash; this.clock = clock; this.hibou = hibou; this.broadcaster = broadcaster;
    }

    // ================================================================== point d'entree

    /** Traite une requete du client (open ou action) et renvoie TOUJOURS une vue a afficher. */
    public View handle(Actor a, String action, Map<String, String> args) {
        String target = args.getOrDefault("_v", "main");
        try {
            rateLimit(a);
            if (action.equals("open")) return render(a, args.getOrDefault("to", "main"), args, null, "info");
            String msg = perform(a, action, args);
            return render(a, target, args, msg, "ok");
        } catch (Throwable t) {
            Throwable u = EconomyException.unwrap(t);
            String text;
            if (u instanceof EconomyException e) text = e.getMessage();
            else { text = "Erreur interne (voir les journaux du serveur)."; System.err.println("[CitizenEconomy] erreur UI : " + u); u.printStackTrace(); }
            String back = action.equals("open") ? args.getOrDefault("_v", "main") : target;
            try { return render(a, back, args, text, "err"); }
            catch (Throwable t2) { return render(a, "main", Map.of(), text, "err"); }
        }
    }

    private void rateLimit(Actor a) {
        ArrayDeque<Long> q = recent.computeIfAbsent(a.id(), k -> new ArrayDeque<>());
        long now = clock.getAsLong();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
            if (q.size() >= 8) throw new EconomyException("RATE", "Trop d'actions : patientez un instant.");
            q.addLast(now);
        }
    }

    private View render(Actor a, String id, Map<String, String> args, String msg, String kind) {
        ViewBuilder b = switch (id) {
            case "main" -> vMain(a, args);
            case "bank" -> vBank(a, args);
            case "create" -> vCreate(a, args);
            case "companies" -> vCompanies(a, args);
            case "company" -> vCompany(a, args);
            case "employees" -> vEmployees(a, args);
            case "perms" -> vPerms(a, args);
            case "salaryhist" -> vSalaryHist(a, args);
            case "cbank" -> vCompanyBank(a, args);
            case "cboard" -> vCompanyBoard(a, args);
            case "board" -> vBoard(a, args);
            case "invoices" -> vInvoices(a, args);
            case "cinvoices" -> vCompanyInvoices(a, args);
            case "tax" -> vTax(a, args);
            case "ctax" -> vCompanyTax(a, args);
            case "notifs" -> vNotifs(a, args);
            case "pos" -> vPos(a, args);
            case "stock" -> vStock(a, args);
            case "stock_item" -> vStockItem(a, args);
            case "sales" -> vSales(a, args);
            case "buyback" -> vBuyback(a, args);
            case "sale" -> vSale(a, args);
            case "acct" -> vAcct(a, args);
            case "acct_journal" -> vAcctJournal(a, args);
            case "acct_manual" -> vAcctManual(a, args);
            case "census" -> vCensus(a, args);
            case "adm_census" -> vAdmCensus(a, args);
            case "adm_census_p" -> vAdmCensusPlayer(a, args);
            case "admin", "treasury", "adm_staff", "adm_companies", "adm_requests", "adm_reqinfo", "adm_players", "adm_members", "adm_tax", "adm_tx",
                 "adm_taxhist", "adm_ann", "adm_audit", "adm_settings", "adm_limits" -> vAdmin(a, id, args);
            default -> throw new EconomyException("BAD_VIEW", "Écran inconnu.");
        };
        return b.message(msg, kind).build();
    }

    // ================================================================== utilitaires

    private ViewBuilder vb(String id, String title, Map<String, String> args) {
        Map<String, String> p = new LinkedHashMap<>();
        for (String k : KEEP) if (args.containsKey(k)) p.put(k, args.get(k));
        return new ViewBuilder(id, title, p);
    }

    private ZoneId zone() {
        long now = clock.getAsLong();
        if (now - zoneAt > 60_000) {
            try { zone = ZoneId.of(eco.admin.timeZone().join()); } catch (RuntimeException ignored) { }
            zoneAt = now;
        }
        return zone;
    }

    private String date(long ts) {
        return DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(zone()).format(Instant.ofEpochMilli(ts));
    }

    private String day(long ts) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(zone()).format(Instant.ofEpochMilli(ts));
    }

    static String money(long v) {
        String s = Long.toString(Math.abs(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (v < 0 ? "-" : "") + b + " PO";
    }

    private static String clip(String s, int max) { return s == null ? "" : (s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"); }

    static String statusFr(String s) {
        return switch (s) {
            case "PENDING" -> "En attente"; case "ACTIVE" -> "Active"; case "SUSPENDED" -> "Suspendue"; case "CLOSED" -> "Fermée";
            case "PAID" -> "Payée"; case "REFUSED" -> "Refusée"; case "CANCELLED" -> "Annulée"; case "OVERDUE" -> "En retard";
            case "ACCEPTED" -> "Acceptée"; case "CHANGES_REQUESTED" -> "Modifications demandées"; case "DUE" -> "À régler";
            case "WAIVED" -> "Exonérée"; case "REMOVED" -> "Retirée"; default -> s;
        };
    }

    static String roleFr(CompanyRole r) { return switch (r) { case OWNER -> "Propriétaire"; case MANAGER -> "Gérant"; case EMPLOYEE -> "Employé"; }; }

    static String permFr(Permission p) {
        return switch (p) {
            case MANAGE_EMPLOYEES -> "Gérer les employés"; case MANAGE_SALARIES -> "Gérer les salaires";
            case MANAGE_PERMISSIONS -> "Gérer les permissions"; case MANAGE_FINANCES -> "Gérer les finances";
            case MANAGE_ANNOUNCEMENTS -> "Gérer toutes les annonces"; case PUBLISH_ANNOUNCEMENTS -> "Publier des annonces";
            case VIEW_FINANCES -> "Voir les finances"; case ISSUE_INVOICES -> "Émettre des factures";
            case CASHIER -> "Tenir la caisse"; case MANAGE_STOCK -> "Gérer le stock"; case VIEW_ACCOUNTING -> "Comptabilité";
        };
    }

    static String categoryFr(AnnouncementCategory c) {
        return switch (c) {
            case RECRUITMENT -> "Recrutement"; case PROMOTION -> "Promotion"; case OPENING -> "Ouverture"; case CLOSING -> "Fermeture";
            case EVENT -> "Événement"; case OFFER -> "Offre"; case SUPPLIERS -> "Fournisseurs"; case MEETING -> "Réunion";
            case INSTRUCTION -> "Consigne"; case INFO -> "Information"; case ORGANIZATION -> "Organisation";
        };
    }

    static String taxFr(TaxKind k) {
        return switch (k) { case BUSINESS_INCOME -> "Taxe entreprises"; case PERSONAL_INCOME -> "Taxe revenus personnels"; case SALARY -> "Taxe salaires"; };
    }

    static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toLowerCase(Locale.ROOT);
    }

    static String pct(int bp) { return String.format(Locale.ROOT, "%.2f %%", bp / 100.0).replace('.', ','); }

    static String periodText(int minutes) {
        return minutes == TaxPeriods.WEEKLY ? "hebdomadaire (dimanche 00h00)" : minutes % 1440 == 0 ? "tous les " + (minutes / 1440) + " jour(s)" : minutes + " minutes";
    }

    private Member self(Actor a, long cid) {
        if (a.isCouncil()) return PermissionManager.councilMember(cid);
        return eco.employees.members(a, cid).join().stream().filter(m -> m.player().equals(a.id())).findFirst().orElse(null);
    }

    private static boolean can(Actor a, Member m, Permission p) { return a.admin() || (m != null && PermissionManager.has(m, p)); }

    private static String arg(Map<String, String> g, String k) { return g.getOrDefault(k, "").trim(); }

    private static long lng(Map<String, String> g, String k) {
        try { return Long.parseLong(arg(g, k)); } catch (NumberFormatException e) { throw new EconomyException("BAD_ARG", "Paramètre invalide : " + k); }
    }

    private static UUID uuid(Map<String, String> g, String k) {
        try { return UUID.fromString(arg(g, k)); } catch (IllegalArgumentException e) { throw new EconomyException("BAD_ARG", "Paramètre invalide : " + k); }
    }

    /** Montant entier strictement positif (espaces tolérés). */
    static long amount(String s) {
        String t = s == null ? "" : s.replaceAll("[\\s\\u00a0\\u202f]", "").replace("PO", "").replace("po", "");
        if (!t.matches("\\d{1,13}")) throw new EconomyException("BAD_AMOUNT", "Montant invalide (nombre entier positif attendu).");
        long v = Long.parseLong(t);
        if (v <= 0) throw new EconomyException("BAD_AMOUNT", "Le montant doit être supérieur à 0.");
        return v;
    }

    static long amountOrZero(String s) { return s == null || s.isBlank() ? 0 : (s.trim().equals("0") ? 0 : amount(s)); }

    private static int intArg(String s, String name) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); } catch (NumberFormatException e) { throw new EconomyException("BAD_ARG", name + " : nombre entier attendu."); }
    }

    static int rateBp(String s) {
        try {
            return new BigDecimal((s == null ? "" : s).replace(',', '.').replace("%", "").trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (RuntimeException e) { throw new EconomyException("BAD_RATE", "Taux invalide (ex : 10 ou 12,5)."); }
    }

    private Long expiryFromDays(String s) {
        if (s == null || s.isBlank()) return null;
        int d = intArg(s, "Expiration (jours)");
        if (d < 1 || d > 365) throw new EconomyException("BAD_EXPIRY", "Expiration : 1 à 365 jours.");
        return clock.getAsLong() + d * DAY;
    }

    private AnnouncementCategory category(String s) {
        if (s == null || s.isBlank()) return AnnouncementCategory.INFO;
        String n = norm(s);
        for (AnnouncementCategory c : AnnouncementCategory.values())
            if (n.equals(norm(c.name())) || n.equals(norm(categoryFr(c)))) return c;
        throw new EconomyException("BAD_TEXT", "Catégorie inconnue. Valeurs : " + categoryList());
    }

    static String categoryList() {
        StringJoiner j = new StringJoiner(", ");
        for (AnnouncementCategory c : AnnouncementCategory.values()) j.add(categoryFr(c));
        return j.toString();
    }

    private static final java.util.regex.Pattern HIBOU_NUMBER = java.util.regex.Pattern.compile("^(?:(?:#|n°|no\\.?|n\\.)\\s*)?(\\d{1,9})$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Numéro de hibou saisi ("7", "0007", "#7", "n°7"), ou vide si ce n'en est pas un. */
    private static OptionalInt hibouNumber(String raw) {
        java.util.regex.Matcher m = HIBOU_NUMBER.matcher(raw == null ? "" : raw.trim());
        return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /** Joueur désigné par son numéro de hibou (si le mod Hibou est là) ou, à défaut, par son pseudo. */
    private PlayerManager.PlayerRef player(String name) {
        if (hibou.available()) {
            OptionalInt n = hibouNumber(name);
            if (n.isPresent()) return playerByHibou(n.getAsInt());
        }
        return eco.players.findByName(name).join()
                .orElseThrow(() -> new EconomyException("TARGET_UNKNOWN", "Joueur inconnu (il doit s'être connecté au moins une fois) : " + clip(name, 20)));
    }

    private PlayerManager.PlayerRef playerByHibou(int number) {
        UUID id = hibou.findByNumber(number).orElseThrow(() -> new EconomyException("TARGET_UNKNOWN", "Aucun hibou ne porte le numéro " + HibouDirectory.format(number) + "."));
        return eco.players.findByUuid(id).join()
                .orElseThrow(() -> new EconomyException("TARGET_UNKNOWN", "Le propriétaire du hibou " + HibouDirectory.format(number) + " ne s'est pas encore connecté à l'économie."));
    }

    /** Destinataire d'une facture à un joueur : numéro de hibou obligatoire quand le mod Hibou est installé. */
    private PlayerManager.PlayerRef invoiceRecipient(String raw) {
        if (!hibou.available()) return player(raw);
        OptionalInt n = hibouNumber(raw);
        if (n.isEmpty()) throw new EconomyException("BAD_ARG", "Une facture à un joueur se fait avec son numéro de hibou (ex : 0007), pas avec son pseudo.");
        return playerByHibou(n.getAsInt());
    }

    private String hibouTag(String playerUuid) {
        if (!hibou.available()) return "";
        try {
            int n = hibou.numberOf(UUID.fromString(playerUuid));
            return n > 0 ? " (hibou n°" + HibouDirectory.format(n) + ")" : "";
        } catch (IllegalArgumentException e) { return ""; }
    }

    private Company companyByName(String name) {
        return eco.companies.findActiveByName(name).join().orElseThrow(() -> new EconomyException("TARGET_UNKNOWN", "Entreprise active introuvable : " + clip(name, 30)));
    }

    /** "@Nom" = entreprise, sinon pseudo de joueur. */
    private AccountRef recipient(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("@")) return AccountRef.business(companyByName(t.substring(1)).id());
        return AccountRef.personal(player(t).uuid());
    }

    /** Identifiant de transaction derive du joueur, de l'action et du jeton de l'ecran : un double clic ne deplace l'argent qu'une fois. */
    private UUID txId(Actor a, Map<String, String> g, String action) {
        String n = g.get("_n");
        if (n == null || n.isBlank()) return UUID.randomUUID();
        return UUID.nameUUIDFromBytes((a.id() + ":" + n + ":" + action).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void requireAdmin(Actor a) { if (!a.admin()) throw new EconomyException("FORBIDDEN", "Réservé à l'administration."); }

    // ================================================================== ecrans joueur

    private ViewBuilder vMain(Actor a, Map<String, String> args) {
        long bal = eco.bank.personalBalance(a).join();
        long unread = eco.notifications.unreadCount(a.id()).join();
        ViewBuilder b = vb("main", "Citizen Economy", args);
        b.h("Bienvenue, " + a.name());
        b.g("Solde personnel : " + money(bal));
        if (unread > 0) b.r("Notifications non lues : " + unread); else b.m("Aucune nouvelle notification.");
        b.blank();
        b.m("Pièces : bronze 1 · argent 10 · or 100 · platine 1 000 PO");
        b.button(ViewBuilder.nav("Banque", "bank")).button(ViewBuilder.nav("Mes entreprises", "companies"))
                .button(ViewBuilder.nav("Annonces", "board")).button(ViewBuilder.nav("Mes factures", "invoices"))
                .button(ViewBuilder.nav("Mes impôts", "tax")).button(ViewBuilder.nav("Notifications", "notifs"))
                .button(ViewBuilder.nav("Créer une entreprise", "create")).button(ViewBuilder.nav("Ma fiche de citoyen", "census"));
        if (eco.census.canCensus(a).join()) b.button(ViewBuilder.nav("Recensement des citoyens", "adm_census"));
        if (a.admin()) b.button(ViewBuilder.nav("Administration", "admin")).button(ViewBuilder.nav("Trésorerie royale", "treasury"));
        return b;
    }

    private void historyLines(ViewBuilder b, List<LedgerLine> hist) {
        if (hist.isEmpty()) b.m("Aucune opération.");
        for (LedgerLine l : hist)
            b.line(date(l.createdAt()) + "  " + (l.delta() > 0 ? "+" : "") + money(l.delta()) + "  " + clip(l.description(), 28) + "  (solde " + money(l.balanceAfter()) + ")",
                    l.delta() > 0 ? "n" : "r");
    }

    private ViewBuilder vBank(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("bank", "Banque personnelle", args);
        b.g("Solde : " + money(eco.bank.personalBalance(a).join()));
        b.h("Dernières opérations");
        historyLines(b, eco.bank.personalHistory(a, 12, 0).join());
        b.field("f_amount", "Montant (PO)", "", 14).field("f_target", "Destinataire (pseudo)", "", 32).field("f_desc", "Description", "", 80);
        b.button(ViewBuilder.nav("Retour", "main")).button(b.act("Déposer", "bank.deposit")).button(b.act("Retirer", "bank.withdraw")).button(b.act("Virer", "bank.transfer"));
        return b;
    }

    private ViewBuilder vCreate(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("create", "Créer une entreprise", args);
        List<CompanyRequest> reqs = eco.companies.myRequests(a.id()).join();
        CompanyRequest toFix = null;
        b.h("Mes demandes");
        if (reqs.isEmpty()) b.m("Aucune demande pour le moment.");
        for (CompanyRequest r : reqs) {
            b.line("« " + r.name() + " » : " + statusFr(r.status().name()), r.status() == RequestStatus.REFUSED ? "r" : "n");
            if (r.adminNote() != null && !r.adminNote().isBlank()) b.m("   Note de l'administration : " + clip(r.adminNote(), 90));
            if (r.status() == RequestStatus.CHANGES_REQUESTED && toFix == null) toFix = r;
        }
        b.blank();
        b.field("f_name", "Nom (3-32)", toFix == null ? "" : toFix.name(), 32).field("f_sector", "Secteur d'activité", toFix == null ? "" : toFix.sector(), 32)
                .field("f_desc", "Description", toFix == null ? "" : toFix.description(), 200).field("f_emblem", "Emblème (3 lettres) : " + Emblems.keys(), toFix == null ? "" : toFix.emblem(), 40);
        b.button(ViewBuilder.nav("Retour", "main")).button(b.act("Envoyer la demande", "req.submit"));
        if (toFix != null) b.button(b.act("Renvoyer la demande corrigée", "req.update", "r", Long.toString(toFix.id())));
        return b;
    }

    private ViewBuilder vCompanies(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("companies", a.isCouncil() ? "Entreprises du royaume" : "Mes entreprises", args);
        List<Company> list = a.isCouncil() ? eco.companies.listAll(a).join() : eco.companies.companiesOf(a.id()).join();
        if (list.isEmpty()) b.m("Vous ne faites partie d'aucune entreprise.");
        for (Company c : list) b.line(c.name() + "  (" + c.matricule() + ")  [" + statusFr(c.status().name()) + "]  " + c.sector(), c.status() == CompanyStatus.ACTIVE ? "n" : "r",
                ViewBuilder.nav("Ouvrir", "company", "id", Long.toString(c.id())));
        b.button(ViewBuilder.nav("Retour", "main")).button(ViewBuilder.nav("Créer une entreprise", "create"));
        if (a.isCouncil()) b.button(ViewBuilder.nav("Administration", "admin")).button(ViewBuilder.nav("Trésorerie royale", "treasury"));
        return b;
    }

    private ViewBuilder vCompany(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("company", c.name(), args);
        b.g(c.name() + " — " + c.sector() + " [" + statusFr(c.status().name()) + "]");
        b.m("Matricule : " + c.matricule() + "  (à communiquer pour être payé ou facturé)");
        if (!c.description().isBlank()) b.n(clip(c.description(), 100));
        if (!c.emblem().isBlank() && c.emblem() != null) b.m("Emblème : " + Emblems.label(c.emblem()));
        b.m("Créée le " + day(c.createdAt()) + (me != null ? " · Vous : " + roleFr(me.role()) + " (" + me.grade() + ")" : ""));
        if (c.status() != CompanyStatus.ACTIVE && c.statusReason() != null) b.r("Motif : " + clip(c.statusReason(), 90));
        if (can(a, me, Permission.VIEW_FINANCES)) {
            b.g("Solde de la caisse : " + money(eco.bank.businessBalance(a, cid).join()));
            BankManager.CompanyStats w = eco.bank.companyStats(a, cid, clock.getAsLong() - 7 * DAY).join();
            BankManager.CompanyStats all = eco.bank.companyStats(a, cid, 0).join();
            b.n("7 derniers jours : CA " + money(w.revenue()) + " · bénéfice " + money(w.profit()));
            b.n("Depuis la création : CA " + money(all.revenue()) + " · bénéfice " + money(all.profit()));
        }
        String ids = Long.toString(cid);
        b.button(ViewBuilder.nav("Retour", "companies")).button(ViewBuilder.nav("Employés", "employees", "id", ids))
                .button(ViewBuilder.nav("Annonces", "cboard", "id", ids)).button(ViewBuilder.nav("Factures", "cinvoices", "id", ids));
        if (can(a, me, Permission.VIEW_FINANCES)) b.button(ViewBuilder.nav("Finances", "cbank", "id", ids)).button(ViewBuilder.nav("Impôts", "ctax", "id", ids));
        if (can(a, me, Permission.CASHIER) || canRead(a, me)) b.button(ViewBuilder.nav("Caisse", "pos", "id", ids)).button(ViewBuilder.nav("Stock", "stock", "id", ids));
        if (can(a, me, Permission.VIEW_ACCOUNTING)) b.button(ViewBuilder.nav("Comptabilité", "acct", "id", ids));
        if (can(a, me, Permission.MANAGE_STOCK)) b.button(ViewBuilder.nav("Rachat de minerais", "buyback", "id", ids));
        return b;
    }

    private ViewBuilder vEmployees(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        List<Member> members = eco.employees.members(a, cid).join();
        ViewBuilder b = vb("employees", "Employés — " + c.name(), args);
        boolean manage = can(a, me, Permission.MANAGE_EMPLOYEES);
        for (Member m : members) {
            String ids = Long.toString(cid), pid = m.player().toString();
            boolean canSee = can(a, me, Permission.MANAGE_SALARIES) || m.player().equals(a.id());
            List<Btn> top = new ArrayList<>();
            if (canSee) top.add(ViewBuilder.nav("Historique", "salaryhist", "id", ids, "p", pid));
            if (can(a, me, Permission.MANAGE_PERMISSIONS) && m.role() != CompanyRole.OWNER) top.add(ViewBuilder.nav("Droits", "perms", "id", ids, "p", pid));
            b.line(m.name() + " — " + m.grade() + " (" + roleFr(m.role()) + ")" + (canSee ? " — " + money(m.salary()) : ""), m.role() == CompanyRole.OWNER ? "g" : "n", top.toArray(new Btn[0]));
            boolean ownRow = me != null && me.role() == CompanyRole.OWNER && m.player().equals(a.id()); // le patron règle son propre salaire
            boolean manageable = a.admin() || a.isCouncil() || ownRow || (me != null && m.role() != CompanyRole.OWNER && !m.player().equals(a.id())
                    && (me.role() == CompanyRole.OWNER || (me.role() == CompanyRole.MANAGER && m.role() == CompanyRole.EMPLOYEE)));
            if (manageable) {
                List<Btn> acts = new ArrayList<>();
                if (can(a, me, Permission.MANAGE_SALARIES)) acts.add(b.act("Salaire", "emp.salary", "p", pid));
                if (manage) {
                    acts.add(b.act("Grade", "emp.grade", "p", pid));
                    if (m.role() != CompanyRole.OWNER) acts.add(ViewBuilder.confirm(b.act("Licencier", "emp.fire", "p", pid), "Licencier " + m.name() + " ?"));
                }
                if (!acts.isEmpty()) b.m("   Actions (utilisent les champs ci-dessous)", acts.toArray(new Btn[0]));
            }
        }
        if (manage) b.field("f_player", "Joueur à recruter (pseudo)", "", 32);
        if (manage || can(a, me, Permission.MANAGE_SALARIES)) b.field("f_salary", "Salaire (PO par période)", "", 14);
        if (manage) b.field("f_grade", "Grade", "", 32);
        b.button(ViewBuilder.nav("Retour", a.admin() && me == null ? "adm_companies" : "company", "id", Long.toString(cid)));
        if (manage) b.button(b.act("Recruter", "emp.hire"));
        return b;
    }

    private ViewBuilder vPerms(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        UUID pid = uuid(args, "p");
        Member me = self(a, cid);
        if (!can(a, me, Permission.MANAGE_PERMISSIONS)) throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
        Member t = eco.employees.members(a, cid).join().stream().filter(m -> m.player().equals(pid)).findFirst()
                .orElseThrow(() -> new EconomyException("NOT_FOUND", "Membre introuvable."));
        ViewBuilder b = vb("perms", "Droits de " + t.name(), args);
        b.g(t.name() + " — " + roleFr(t.role()));
        for (Permission p : Permission.values()) {
            boolean on = PermissionManager.has(t, p);
            boolean locked = t.role() == CompanyRole.EMPLOYEE && p.managerOnly;
            if (locked) b.m((on ? "✔ " : "✘ ") + permFr(p) + " (réservé aux gérants)");
            else b.line((on ? "✔ " : "✘ ") + permFr(p), on ? "g" : "m", b.act(on ? "Retirer" : "Accorder", "emp.perm", "perm", p.name()));
        }
        b.button(ViewBuilder.nav("Retour", "employees", "id", Long.toString(cid)));
        if (a.admin() || (me != null && me.role() == CompanyRole.OWNER)) {
            if (t.role() == CompanyRole.EMPLOYEE) b.button(b.act("Nommer gérant", "emp.promote"));
            if (t.role() == CompanyRole.MANAGER) b.button(b.act("Retirer le gérant", "emp.demote"));
        }
        return b;
    }

    private ViewBuilder vSalaryHist(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        UUID pid = uuid(args, "p");
        ViewBuilder b = vb("salaryhist", "Historique salarial", args);
        List<SalaryChange> h = eco.employees.salaryHistory(a, cid, pid).join();
        if (h.isEmpty()) b.m("Aucun historique.");
        for (SalaryChange c : h) b.n(date(c.changedAt()) + "  " + money(c.oldSalary()) + " → " + money(c.newSalary()));
        b.button(ViewBuilder.nav("Retour", "employees", "id", Long.toString(cid)));
        return b;
    }

    private ViewBuilder vCompanyBank(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("cbank", "Finances — " + c.name(), args);
        b.g("Solde : " + money(eco.bank.businessBalance(a, cid).join()));
        b.h("Historique financier");
        historyLines(b, eco.bank.businessHistory(a, cid, 12, 0).join());
        b.field("f_amount", "Montant (PO)", "", 14);
        if (can(a, me, Permission.MANAGE_FINANCES)) b.field("f_target", "Payer à : pseudo ou @Entreprise / @matricule", "", 32).field("f_desc", "Description", "", 80);
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid))).button(b.act("Apport (mon compte → caisse)", "cbank.deposit"));
        if (can(a, me, Permission.MANAGE_FINANCES)) b.button(b.act("Retrait (caisse → mon compte)", "cbank.withdraw")).button(b.act("Payer", "cbank.pay"));
        return b;
    }

    private ViewBuilder vCompanyBoard(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("cboard", "Annonces — " + c.name(), args);
        if (c.announcementsSuspended()) b.r("Les publications de cette entreprise sont suspendues par l'administration.");
        List<Announcement> list = eco.announcements.companyBoard(a, cid, LIST, 0).join();
        if (list.isEmpty()) b.m("Aucune annonce.");
        for (Announcement an : list) {
            boolean own = an.author().equals(a.id());
            boolean manageAll = can(a, me, Permission.MANAGE_ANNOUNCEMENTS);
            List<Btn> btns = new ArrayList<>();
            if ((!a.admin() || a.isCouncil()) && (own || manageAll)) {
                btns.add(b.act("Modifier", "ann.edit", "a", Long.toString(an.id())));
                btns.add(ViewBuilder.confirm(b.act("Suppr.", "ann.delete", "a", Long.toString(an.id())), "Supprimer cette annonce ?"));
            }
            b.line((an.visibility() == AnnouncementVisibility.PUBLIC ? "[Public] " : "[Interne] ") + categoryFr(an.category()) + " : " + clip(an.title(), 40)
                    + " — " + an.authorName() + " (" + date(an.createdAt()) + ")", an.visibility() == AnnouncementVisibility.PUBLIC ? "g" : "n", btns.toArray(new Btn[0]));
            b.m("   " + clip(an.body(), 90));
        }
        if (me != null) {
            b.m("Catégories : " + categoryList());
            b.field("f_title", "Titre", "", 60).field("f_body", "Description", "", 300).field("f_cat", "Catégorie", "Information", 20).field("f_days", "Expire dans (jours, facultatif)", "", 3);
        }
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid)));
        if (me != null && can(a, me, Permission.PUBLISH_ANNOUNCEMENTS))
            b.button(b.act("Publier (public)", "ann.publish", "vis", "PUBLIC")).button(b.act("Publier (interne)", "ann.publish", "vis", "INTERNAL"));
        return b;
    }

    private ViewBuilder vBoard(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("board", "Panneau des annonces", args);
        List<Announcement> list = eco.announcements.publicBoard(null, 30, 0).join();
        if (list.isEmpty()) b.m("Aucune annonce publique pour le moment.");
        for (Announcement an : list) {
            b.g("[" + categoryFr(an.category()) + "] " + clip(an.title(), 44) + " — " + an.companyName());
            b.n("   " + clip(an.body(), 100));
            b.m("   par " + an.authorName() + " le " + date(an.createdAt()) + (an.expiresAt() != null ? " · expire le " + day(an.expiresAt()) : ""));
        }
        b.button(ViewBuilder.nav("Retour", "main")).button(ViewBuilder.nav("Mes entreprises", "companies"));
        return b;
    }

    private void invoiceLine(ViewBuilder b, Invoice i, String who, List<Btn> btns) {
        boolean open = i.status().equals("PENDING") || i.status().equals("OVERDUE");
        b.line(i.number() + "  " + who + "  " + money(i.amount()) + "  [" + statusFr(i.status()) + "]" + (i.dueAt() != null && open ? "  avant le " + day(i.dueAt()) : ""),
                i.status().equals("OVERDUE") ? "r" : open ? "n" : "m", btns.toArray(new Btn[0]));
        if (!i.description().isBlank()) b.m("   " + clip(i.description(), 90));
    }

    private ViewBuilder vInvoices(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("invoices", "Mes factures", args);
        List<Invoice> list = eco.invoices.myInvoices(a, null, 30).join();
        if (list.isEmpty()) b.m("Aucune facture.");
        for (Invoice i : list) {
            boolean open = i.status().equals("PENDING") || i.status().equals("OVERDUE");
            List<Btn> btns = new ArrayList<>();
            if (open) {
                btns.add(ViewBuilder.confirm(b.act("Payer", "inv.pay", "i", Long.toString(i.id())), "Payer " + money(i.amount()) + " à " + i.issuerName() + " ?"));
                btns.add(b.act("Refuser", "inv.refuse", "i", Long.toString(i.id())));
            }
            invoiceLine(b, i, "de " + i.issuerName(), btns);
        }
        b.button(ViewBuilder.nav("Retour", "main"));
        return b;
    }

    private ViewBuilder vCompanyInvoices(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("cinvoices", "Factures — " + c.name(), args);
        b.h("Émises");
        List<Invoice> issued = eco.invoices.issuedByCompany(a, cid, null, 15).join();
        if (issued.isEmpty()) b.m("Aucune facture émise.");
        for (Invoice i : issued) {
            boolean open = i.status().equals("PENDING") || i.status().equals("OVERDUE");
            List<Btn> btns = new ArrayList<>();
            if (open && me != null && can(a, me, Permission.ISSUE_INVOICES)) btns.add(ViewBuilder.confirm(b.act("Annuler", "inv.cancel", "i", Long.toString(i.id())), "Annuler cette facture ?"));
            invoiceLine(b, i, "à " + i.recipientName() + (i.recipientType().equals("PLAYER") ? hibouTag(i.recipientId()) : ""), btns);
        }
        if (can(a, me, Permission.VIEW_FINANCES)) {
            b.h("Reçues");
            List<Invoice> recv = eco.invoices.receivedByCompany(a, cid, null, 15).join();
            if (recv.isEmpty()) b.m("Aucune facture reçue.");
            for (Invoice i : recv) {
                boolean open = i.status().equals("PENDING") || i.status().equals("OVERDUE");
                List<Btn> btns = new ArrayList<>();
                if (open && me != null && can(a, me, Permission.MANAGE_FINANCES)) {
                    btns.add(ViewBuilder.confirm(b.act("Payer", "inv.pay", "i", Long.toString(i.id())), "Payer " + money(i.amount()) + " depuis la caisse ?"));
                    btns.add(b.act("Refuser", "inv.refuse", "i", Long.toString(i.id())));
                }
                invoiceLine(b, i, "de " + i.issuerName(), btns);
            }
        }
        boolean issue = me != null && can(a, me, Permission.ISSUE_INVOICES);
        if (issue) b.field("f_target", hibou.available() ? "Destinataire : n° de hibou (joueur) ou nom / matricule (entreprise)" : "Destinataire (pseudo, nom ou matricule d'entreprise)", "", 32).field("f_desc", "Description", "", 120)
                .field("f_amount", "Montant (PO)", "", 14).field("f_due", "Échéance en jours (facultatif)", "", 3);
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid)));
        if (issue) b.button(b.act(hibou.available() ? "Facturer un joueur (n° hibou)" : "Facturer un joueur", "inv.create", "kind", "player")).button(b.act("Facturer une entreprise", "inv.create", "kind", "company"));
        return b;
    }

    private void taxLines(ViewBuilder b, List<TaxDeclaration> list, boolean payable) {
        if (list.isEmpty()) b.m("Aucune déclaration.");
        for (TaxDeclaration d : list) {
            List<Btn> btns = new ArrayList<>();
            if (payable && d.status().equals("DUE")) btns.add(ViewBuilder.confirm(b.act("Payer", "tax.pay", "d", Long.toString(d.id())), "Payer " + money(d.amount()) + " d'impôt ?"));
            b.line(day(d.periodStart()) + " → " + day(d.periodEnd()) + "  " + taxFr(d.kind()) + "  base " + money(d.baseAmount()) + " × " + pct(d.rateBp())
                    + " = " + money(d.amount()) + "  [" + statusFr(d.status()) + "]", d.status().equals("DUE") ? "r" : "n", btns.toArray(new Btn[0]));
        }
    }

    private ViewBuilder vTax(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("tax", "Mes impôts", args);
        b.h("Taux en vigueur");
        List<TaxRate> rates = eco.tax.publicRates().join();
        if (rates.isEmpty()) b.m("Aucune taxe configurée.");
        for (TaxRate r : rates) b.n(taxFr(r.kind()) + " : " + (r.enabled() ? pct(r.rateBp()) + (r.mode() == TaxMode.AUTO ? " (prélèvement automatique)" : " (déclaration)") : "désactivée"));
        b.h("Mes déclarations");
        taxLines(b, eco.tax.myDeclarations(a, LIST).join(), true);
        b.button(ViewBuilder.nav("Retour", "main"));
        return b;
    }

    private ViewBuilder vCompanyTax(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        ViewBuilder b = vb("ctax", "Impôts — " + c.name(), args);
        taxLines(b, eco.tax.companyDeclarations(a, cid, LIST).join(), self(a, cid) != null);
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid)));
        return b;
    }

    private ViewBuilder vNotifs(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("notifs", "Notifications", args);
        List<NotificationManager.Notif> list = eco.notifications.history(a.id(), 30, 0).join();
        if (list.isEmpty()) b.m("Aucune notification.");
        for (NotificationManager.Notif n : list) b.line(date(n.createdAt()) + "  " + clip(n.message(), 110), n.read() ? "m" : "n");
        b.button(ViewBuilder.nav("Retour", "main")).button(b.act("Tout marquer comme lu", "notif.readall"));
        return b;
    }

    // ================================================================== recensement

    private ViewBuilder vCensus(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("census", "Recensement du royaume", args);
        Optional<CensusManager.Entry> cur = eco.census.get(a.id()).join();
        CensusManager.Entry e = cur.orElse(null);
        String auto = eco.census.autoProfession(a.id()).join();
        String prof = e != null && !e.profession().isEmpty() ? e.profession() + " (fixée par l'administration)" : auto + " (selon votre entreprise)";
        b.h("Fiche de citoyen");
        b.n("Prénom : " + (e == null || e.firstName().isEmpty() ? "à remplir" : e.firstName()));
        b.n("Nom : " + (e == null || e.lastName().isEmpty() ? "à remplir" : e.lastName()));
        b.n("Âge : " + (e == null || e.age() <= 0 ? "à remplir" : e.age() + " ans"));
        b.n("Profession : " + prof);
        b.blank();
        if (e != null && e.validated()) b.g("Recensement validé. Ces informations remplacent votre pseudo dans la liste des joueurs (Tab).");
        else if (e == null) b.r("Vous n'êtes pas encore recensé.");
        else b.r("Votre recensement n'est pas encore validé.");
        b.m("Le recensement est réalisé par les employés de la couronne : présentez-vous à l'un d'eux.");
        b.m("Une erreur sur votre fiche ? Signalez-la à un employé de la couronne ou à l'administration.");
        b.button(ViewBuilder.nav("Retour", "main"));
        if (eco.census.canCensus(a).join()) b.button(ViewBuilder.nav("Recensement des citoyens", "adm_census"));
        return b;
    }

    private ViewBuilder vAdmCensus(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_census", "Recensement des citoyens", args);
        List<CensusManager.Row> rows = eco.census.list(a, 150).join();
        long done = rows.stream().filter(r -> r.entry() != null && r.entry().validated()).count();
        long open = rows.stream().filter(r -> r.entry() != null && !r.entry().validated()).count();
        b.g("Recensés (validés) : " + done + " · en cours : " + open + " · non recensés : " + (rows.size() - done - open));
        if (rows.isEmpty()) b.m("Aucun joueur connu.");
        for (CensusManager.Row r : rows) {
            CensusManager.Entry e = r.entry();
            Btn open1 = ViewBuilder.nav("Fiche", "adm_census_p", "p", r.player().toString());
            if (e == null) { b.m(r.playerName() + " — non recensé", open1); continue; }
            String txt = r.playerName() + " → " + (e.firstName() + " " + e.lastName()).trim() + ", " + (e.age() > 0 ? e.age() + " ans" : "âge ?")
                    + (e.profession().isEmpty() ? "" : ", " + e.profession());
            b.line(txt + (e.validated() ? "" : "  (non validé)"), e.validated() ? "n" : "r", open1);
        }
        b.button(ViewBuilder.nav("Retour", a.admin() ? "admin" : "main"));
        return b;
    }

    private ViewBuilder vAdmCensusPlayer(Actor a, Map<String, String> args) {
        UUID pid = uuid(args, "p");
        String pname = eco.players.findByUuid(pid).join().map(PlayerManager.PlayerRef::name).orElse(pid.toString());
        ViewBuilder b = vb("adm_census_p", "Recensement — " + pname, args);
        CensusManager.Entry e = eco.census.get(pid).join().orElse(null);
        String auto = eco.census.autoProfession(pid).join();
        b.h("Pseudo Minecraft : " + pname);
        if (e == null) b.r("Ce joueur n'est pas encore recensé : remplissez sa fiche, enregistrez, puis validez.");
        else {
            b.n("Prénom : " + e.firstName() + " · Nom : " + e.lastName() + " · Âge : " + (e.age() > 0 ? e.age() + " ans" : "?"));
            b.n("Profession affichée : " + (e.profession().isEmpty() ? auto + " (automatique)" : e.profession() + " (fixée)"));
            if (e.validated()) b.g("Fiche validée (" + date(e.updatedAt()) + ")."); else b.r("Fiche non validée.");
        }
        b.m("Profession automatique (entreprise) : " + auto + ". Laissez le champ Profession vide pour la garder.");
        b.field("f_first", "Prénom", e == null ? "" : e.firstName(), CensusManager.MAX_NAME)
                .field("f_last", "Nom de famille", e == null ? "" : e.lastName(), CensusManager.MAX_NAME)
                .field("f_age", "Âge", e == null || e.age() <= 0 ? "" : Integer.toString(e.age()), 3)
                .field("f_prof", "Profession (vide = automatique)", e == null ? "" : e.profession(), CensusManager.MAX_PROFESSION);
        b.button(ViewBuilder.nav("Retour", "adm_census")).button(b.act("Enregistrer", "adm.census.save"));
        if (e != null) {
            if (e.validated()) b.button(b.act("Rouvrir au joueur", "adm.census.unlock"));
            else if (e.complete()) b.button(b.act("Valider", "adm.census.validate"));
            b.button(ViewBuilder.confirm(b.act("Effacer", "adm.census.delete"), "Effacer le recensement de " + pname + " ?"));
        }
        return b;
    }

    // ================================================================== ecrans administrateur

    private ViewBuilder vAdmin(Actor a, String id, Map<String, String> args) {
        requireAdmin(a);
        return switch (id) {
            case "admin" -> vDashboard(a, args);
            case "treasury" -> vTreasury(a, args);
            case "adm_companies" -> vAdmCompanies(a, args);
            case "adm_requests" -> vAdmRequests(a, args);
            case "adm_reqinfo" -> vAdmReqInfo(a, args);
            case "adm_players" -> vAdmPlayers(a, args);
            case "adm_members" -> vAdmMembers(a, args);
            case "adm_tax" -> vAdmTax(a, args);
            case "adm_tx" -> vAdmTx(a, args);
            case "adm_taxhist" -> vAdmTaxHist(a, args);
            case "adm_ann" -> vAdmAnn(a, args);
            case "adm_audit" -> vAdmAudit(a, args);
            case "adm_settings" -> vAdmSettings(a, args);
            case "adm_limits" -> vAdmLimits(a, args);
            case "adm_staff" -> vAdmStaff(a, args);
            default -> throw new EconomyException("BAD_VIEW", "Écran inconnu.");
        };
    }

    private ViewBuilder vDashboard(Actor a, Map<String, String> args) {
        AdminManager.Dashboard d = eco.admin.dashboard(a).join();
        ViewBuilder b = vb("admin", "Administration du royaume", args);
        b.h("Tableau de bord");
        b.g("Trésor royal : " + money(d.treasury()));
        Map<String, Long> s = d.companiesByStatus();
        b.n("Entreprises : " + s.get("ACTIVE") + " actives · " + s.get("SUSPENDED") + " suspendues · " + s.get("CLOSED") + " fermées");
        if (d.pendingRequests() > 0) b.r("Demandes de création en attente : " + d.pendingRequests()); else b.m("Aucune demande en attente.");
        b.n("Joueurs connus : " + d.players());
        b.n("Argent en circulation : comptes personnels " + money(d.personalTotal()) + " · comptes professionnels " + money(d.businessTotal()));
        b.n("Factures ouvertes : " + d.openInvoices() + " · Annonces actives : " + d.activeAnnouncements());
        b.button(ViewBuilder.nav("Retour", "main")).button(ViewBuilder.nav("Entreprises", "adm_companies")).button(ViewBuilder.nav("Demandes", "adm_requests"))
                .button(ViewBuilder.nav("Joueurs", "adm_players")).button(ViewBuilder.nav("Recensement", "adm_census")).button(ViewBuilder.nav("Personnel du royaume", "adm_staff")).button(ViewBuilder.nav("Propriétaires", "adm_members", "role", "OWNER"))
                .button(ViewBuilder.nav("Gérants", "adm_members", "role", "MANAGER")).button(ViewBuilder.nav("Employés", "adm_members", "role", "EMPLOYEE"))
                .button(ViewBuilder.nav("Taxes", "adm_tax")).button(ViewBuilder.nav("Trésorerie", "treasury")).button(ViewBuilder.nav("Hist. bancaire", "adm_tx"))
                .button(ViewBuilder.nav("Hist. fiscal", "adm_taxhist")).button(ViewBuilder.nav("Annonces", "adm_ann"))
                .button(ViewBuilder.nav("Suspension", "adm_companies", "mode", "suspend")).button(ViewBuilder.nav("Fermeture", "adm_companies", "mode", "close"))
                .button(ViewBuilder.nav("Journal", "adm_audit")).button(ViewBuilder.nav("Paramètres", "adm_settings"));
        return b;
    }

    private static String txLabel(String type) {
        return switch (type) {
            case "TAX" -> "Taxes"; case "ROYAL_EXPENSE" -> "Dépenses royales"; case "ROYAL_REVENUE" -> "Revenus administratifs";
            case "DEPOSIT" -> "Dépôt"; case "WITHDRAWAL" -> "Retrait"; case "TRANSFER" -> "Virement"; case "SALARY" -> "Salaire";
            case "INVOICE_PAYMENT" -> "Facture"; default -> type;
        };
    }

    private ViewBuilder vTreasury(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("treasury", "Trésorerie royale", args);
        b.g("Solde royal : " + money(eco.treasury.balance(a).join()));
        long now = clock.getAsLong();
        b.h("Entrées et sorties (30 derniers jours)");
        List<TreasuryStat> stats = eco.treasury.stats(a, now - 30 * DAY, now + 1).join();
        long in = 0, out = 0;
        if (stats.isEmpty()) b.m("Aucun mouvement.");
        for (TreasuryStat st : stats) { in += st.in(); out += st.out(); b.n(txLabel(st.type().name()) + " : entrées " + money(st.in()) + " · sorties " + money(st.out())); }
        b.g("Total : entrées " + money(in) + " · sorties " + money(out));
        b.h("Historique");
        historyLines(b, eco.treasury.history(a, 10, 0).join());
        b.field("f_amount", "Montant (PO)", "", 14).field("f_target", "Bénéficiaire : pseudo ou @Entreprise / @matricule", "", 32).field("f_reason", "Motif (obligatoire)", "", 120);
        b.button(ViewBuilder.nav("Retour", "admin")).button(ViewBuilder.confirm(b.act("Verser au bénéficiaire", "adm.spend", "to", "target"), "Effectuer cette dépense royale ?"))
                .button(ViewBuilder.confirm(b.act("Retirer en espèces", "adm.spend", "to", "cash"), "Retirer ces fonds en pièces ?")).button(b.act("Revenu en espèces", "adm.revenue"));
        return b;
    }

    private Map<Long, String> companyNames(Actor a) {
        Map<Long, String> m = new HashMap<>();
        for (Company c : eco.companies.listAll(a).join()) m.put(c.id(), c.name());
        return m;
    }

    private ViewBuilder vAdmCompanies(Actor a, Map<String, String> args) {
        String mode = args.getOrDefault("mode", "all");
        ViewBuilder b = vb("adm_companies", switch (mode) { case "suspend" -> "Suspension des entreprises"; case "close" -> "Fermeture des entreprises"; default -> "Gestion des entreprises"; }, args);
        List<Company> list = eco.companies.listAll(a).join();
        int shown = 0;
        for (Company c : list) {
            if (shown++ >= 40) { b.m("… liste tronquée à 40 entreprises."); break; }
            String bal = c.status() == CompanyStatus.CLOSED ? "" : " · caisse " + money(eco.bank.businessBalance(a, c.id()).join());
            String ids = Long.toString(c.id());
            List<Btn> btns = new ArrayList<>();
            switch (mode) {
                case "suspend" -> {
                    if (c.status() == CompanyStatus.ACTIVE) btns.add(ViewBuilder.confirm(b.act("Suspendre", "adm.co.suspend", "id", ids), "Suspendre « " + c.name() + " » ?"));
                    if (c.status() == CompanyStatus.SUSPENDED) btns.add(b.act("Réactiver", "adm.co.activate", "id", ids));
                }
                case "close" -> {
                    if (c.status() == CompanyStatus.ACTIVE || c.status() == CompanyStatus.SUSPENDED)
                        btns.add(ViewBuilder.confirm(b.act("Fermer", "adm.co.close", "id", ids), "Fermer définitivement « " + c.name() + " » ? Cette action est irréversible."));
                }
                default -> {
                    if (c.status() != CompanyStatus.CLOSED) btns.add(ViewBuilder.nav("Membres", "employees", "id", ids));
                    btns.add(b.act("Emblème", "adm.co.emblem", "id", ids));
                }
            }
            b.line(c.matricule() + " " + c.name() + " [" + statusFr(c.status().name()) + "] " + c.sector() + bal + (mode.equals("all") ? " · emblème : " + Emblems.label(c.emblem()) : ""), c.status() == CompanyStatus.ACTIVE ? "n" : "r", btns.toArray(new Btn[0]));
        }
        if (list.isEmpty()) b.m("Aucune entreprise.");
        if (!mode.equals("all")) b.field("f_reason", "Motif (obligatoire)", "", 120);
        else {
            b.m("Emblème = code à 3 lettres lu par Citizen Métiers : " + Emblems.keys() + ". HOP = médecins de l'hôpital (Citizen Médical). BAN et AUC = aucune restriction ; vide = détection d'après le secteur.");
            b.field("f_emblem", "Nouvel emblème (puis bouton « Emblème » de l'entreprise)", "", 40);
        }
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmStaff(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_staff", "Personnel du royaume", args);
        List<RoyalStaffManager.Staff> list = eco.staff.list(a).join();
        b.g("Employés de la couronne : " + list.size() + " · salaires versés par la trésorerie royale à chaque période de paie");
        b.m("Seuls les employés de la couronne (et les admins) peuvent réaliser le recensement : /recensement. « Modifier » utilise les champs du bas (vide = inchangé).");
        if (list.isEmpty()) b.m("Aucun employé pour le moment.");
        for (RoyalStaffManager.Staff s : list) {
            String pid = s.player().toString();
            b.line(s.name() + " — " + s.title() + " — " + money(s.salary()), "n",
                    b.act("Modifier", "adm.staff.set", "p", pid),
                    ViewBuilder.confirm(b.act("Renvoyer", "adm.staff.fire", "p", pid), "Renvoyer " + s.name() + " de la couronne ?"));
        }
        b.field("f_player", "Joueur (pseudo ou n° de hibou)", "", 32).field("f_title", "Titre (ex. Greffier, Scribe)", "", RoyalStaffManager.MAX_TITLE)
                .field("f_salary", "Salaire (PO par période)", "", 14);
        b.button(ViewBuilder.nav("Retour", "admin")).button(b.act("Recruter", "adm.staff.hire"));
        return b;
    }

    private ViewBuilder vAdmRequests(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_requests", "Demandes de création", args);
        List<CompanyRequest> list = eco.companies.pendingRequests(a).join();
        if (list.isEmpty()) b.m("Aucune demande en attente.");
        for (CompanyRequest r : list) {
            String id = Long.toString(r.id());
            b.line("#" + r.id() + " « " + r.name() + " » — " + r.sector() + " — par " + r.applicantName() + " (" + date(r.createdAt()) + ")", "g",
                    ViewBuilder.nav("Infos", "adm_reqinfo", "r", id));
            b.m("   " + clip(r.description(), 100));
            b.m("   Décision :", ViewBuilder.confirm(b.act("Accepter", "adm.req.accept", "r", id), "Accepter « " + r.name() + " » ?"),
                    b.act("Refuser", "adm.req.refuse", "r", id), b.act("Modifs", "adm.req.changes", "r", id));
        }
        b.field("f_note", "Note / motif (obligatoire pour refuser ou demander des modifications)", "", 200);
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmReqInfo(Actor a, Map<String, String> args) {
        long rid = lng(args, "r");
        ApplicantInfo info = eco.companies.applicantInfo(a, rid).join();
        ViewBuilder b = vb("adm_reqinfo", "Informations sur le demandeur", args);
        b.g(info.name());
        b.m("UUID : " + info.uuid());
        b.n("Première connexion : " + date(info.firstSeen()) + " · dernière : " + date(info.lastSeen()));
        b.n("Entreprises actives : " + info.activeCompanies() + " · demandes refusées : " + info.refusedRequests());
        eco.companies.pendingRequests(a).join().stream().filter(r -> r.id() == rid).findFirst().ifPresent(r -> {
            b.h("Demande");
            b.n("Nom : " + r.name() + " · Secteur : " + r.sector());
            b.n("Description : " + clip(r.description(), 200));
            if (r.emblem() != null && !r.emblem().isBlank()) b.n("Emblème : " + r.emblem());
        });
        b.button(ViewBuilder.nav("Retour", "adm_requests"));
        return b;
    }

    private ViewBuilder vAdmPlayers(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_players", "Gestion des joueurs", args);
        List<PlayerManager.PlayerRow> list = eco.players.list(a, 60, 0).join();
        if (list.isEmpty()) b.m("Aucun joueur enregistré.");
        for (PlayerManager.PlayerRow p : list) b.n(p.name() + " — solde " + money(p.balance()) + " — vu le " + date(p.lastSeen()));
        b.m("Les exonérations fiscales se gèrent dans le menu Taxes ; les entreprises dans Propriétaires, Gérants et Employés.");
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmMembers(Actor a, Map<String, String> args) {
        CompanyRole role;
        try { role = CompanyRole.valueOf(args.getOrDefault("role", "EMPLOYEE")); } catch (IllegalArgumentException e) { throw new EconomyException("BAD_ARG", "Rôle invalide."); }
        ViewBuilder b = vb("adm_members", switch (role) { case OWNER -> "Gestion des propriétaires"; case MANAGER -> "Gestion des gérants"; case EMPLOYEE -> "Gestion des employés"; }, args);
        List<AdminManager.AdminMember> list = eco.admin.members(a, role, 60).join();
        if (list.isEmpty()) b.m("Aucun membre.");
        for (AdminManager.AdminMember am : list) {
            Member m = am.member();
            List<Btn> btns = new ArrayList<>();
            String cid = Long.toString(m.companyId()), pid = m.player().toString();
            switch (role) {
                case OWNER -> btns.add(b.act("Transférer", "adm.owner.transfer", "id", cid));
                case MANAGER -> btns.add(ViewBuilder.confirm(b.act("Retirer", "adm.emp.demote", "id", cid), "Retirer le gérant " + m.name() + " ?"));
                case EMPLOYEE -> btns.add(ViewBuilder.confirm(b.act("Licencier", "adm.emp.fire", "id", cid, "p", pid), "Licencier " + m.name() + " ?"));
            }
            b.line(m.name() + " — " + am.companyName() + " · " + m.grade() + " · " + money(m.salary()), "n", btns.toArray(new Btn[0]));
        }
        if (role == CompanyRole.OWNER) b.field("f_player", "Nouveau propriétaire (pseudo, déjà membre)", "", 32).field("f_reason", "Motif", "", 120);
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmTax(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_tax", "Configuration des taxes", args);
        Map<TaxKind, TaxRate> latest = new EnumMap<>(TaxKind.class);
        for (TaxRate r : eco.tax.latestRates(a).join()) latest.put(r.kind(), r);
        long now = clock.getAsLong();
        b.h("Taxes (un changement prend effet à la prochaine période)");
        for (TaxKind k : TaxKind.values()) {
            TaxRate r = latest.get(k);
            String text = taxFr(k) + " : " + (r == null ? "non configurée" : (r.enabled() ? pct(r.rateBp()) : "désactivée") + " · "
                    + (r.mode() == TaxMode.AUTO ? "automatique" : "déclaration") + " · base " + (switch (r.base()) { case REVENUE -> "chiffre d'affaires"; case PROFIT -> "bénéfice"; case SELECTED_INCOME -> "revenus choisis"; case SALARY -> "salaires"; })
                    + (r.effectiveFrom() > now ? " · effet le " + day(r.effectiveFrom()) : ""));
            List<Btn> btns = new ArrayList<>(List.of(b.act("Taux", "adm.tax.rate", "kind", k.name()), b.act(r != null && r.enabled() ? "Désactiver" : "Activer", "adm.tax.toggle", "kind", k.name()),
                    b.act("Mode", "adm.tax.mode", "kind", k.name())));
            if (k == TaxKind.BUSINESS_INCOME) btns.add(b.act("Base", "adm.tax.base", "kind", k.name()));
            b.line(text, r != null && r.enabled() ? "g" : "m", btns.toArray(new Btn[0]));
        }
        b.n("Fréquence : " + periodText(eco.tax.periodMinutes().join()), b.act("Appliquer", "adm.tax.period"));
        Set<TxType> types = eco.tax.personalIncomeTypes().join();
        b.h("Revenus personnels imposables");
        for (TxType t : TaxManager.SELECTABLE_PERSONAL_TYPES) {
            String l = switch (t) { case SALARY -> "Salaires reçus"; case ROYAL_EXPENSE -> "Versements royaux reçus"; default -> "Virements reçus (déconseillé)"; };
            b.line((types.contains(t) ? "✔ " : "✘ ") + l, types.contains(t) ? "g" : "m", b.act("Basculer", "adm.tax.ptype", "type", t.name()));
        }
        b.h("Exonérations actives");
        List<TaxManager.Exemption> ex = eco.tax.exemptions(a).join();
        if (ex.isEmpty()) b.m("Aucune.");
        for (TaxManager.Exemption e : ex) b.n((e.subjectType().equals("PLAYER") ? "Joueur " : "Entreprise ") + e.subjectName() + " — " + taxFr(e.kind()));
        b.field("f_rate", "Taux en % (ex : 10 ou 12,5)", "", 8).field("f_period", "Fréquence en minutes (10080 = hebdomadaire)", "", 8).field("f_subject", "Exonération : pseudo, nom ou matricule d'entreprise", "", 32);
        b.button(ViewBuilder.nav("Retour", "admin")).button(b.act("Exonérer joueur", "adm.tax.exempt", "who", "player")).button(b.act("Exonérer entreprise", "adm.tax.exempt", "who", "company"))
                .button(b.act("Lever exo. joueur", "adm.tax.unexempt", "who", "player")).button(b.act("Lever exo. entreprise", "adm.tax.unexempt", "who", "company"));
        return b;
    }

    private ViewBuilder vAdmTx(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_tx", "Historique bancaire", args);
        List<AdminManager.TxRow> list = eco.admin.transactions(a, 30, 0).join();
        if (list.isEmpty()) b.m("Aucune transaction.");
        for (AdminManager.TxRow t : list) b.n("#" + t.id() + " " + date(t.createdAt()) + " " + txLabel(t.type()) + " " + money(t.amount()) + " : " + clip(t.source(), 16) + " → " + clip(t.target(), 16));
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmTaxHist(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_taxhist", "Historique fiscal", args);
        Map<Long, String> cn = companyNames(a);
        Map<String, String> pn = new HashMap<>();
        for (PlayerManager.PlayerRow p : eco.players.list(a, 100, 0).join()) pn.put(p.uuid().toString(), p.name());
        List<TaxDeclaration> list = eco.tax.allDeclarations(a, null, 30, 0).join();
        if (list.isEmpty()) b.m("Aucune déclaration.");
        for (TaxDeclaration d : list) {
            String who = d.accountType() == AccountType.BUSINESS ? cn.getOrDefault(Long.parseLong(d.accountId()), "?") : pn.getOrDefault(d.accountId(), "?");
            List<Btn> btns = new ArrayList<>();
            if (d.status().equals("DUE")) btns.add(ViewBuilder.confirm(b.act("Lever", "adm.tax.waive", "d", Long.toString(d.id())), "Exonérer cette déclaration ?"));
            b.line("#" + d.id() + " " + who + " · " + taxFr(d.kind()) + " · base " + money(d.baseAmount()) + " × " + pct(d.rateBp()) + " = " + money(d.amount()) + " [" + statusFr(d.status()) + "]",
                    d.status().equals("DUE") ? "r" : "n", btns.toArray(new Btn[0]));
        }
        b.field("f_reason", "Motif (pour lever une déclaration)", "", 120);
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmAnn(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_ann", "Gestion des annonces", args);
        Map<Long, Company> companies = new HashMap<>();
        for (Company c : eco.companies.listAll(a).join()) companies.put(c.id(), c);
        List<Announcement> list = eco.announcements.adminList(a, null, 30, 0).join();
        if (list.isEmpty()) b.m("Aucune annonce.");
        for (Announcement an : list) {
            Company c = companies.get(an.companyId());
            List<Btn> btns = new ArrayList<>();
            if (an.status().equals("ACTIVE")) btns.add(ViewBuilder.confirm(b.act("Retirer", "adm.ann.remove", "a", Long.toString(an.id())), "Retirer cette annonce ?"));
            if (c != null) btns.add(b.act(c.announcementsSuspended() ? "Autoriser pub." : "Suspendre pub.", "adm.ann.pub", "id", Long.toString(c.id()), "on", c.announcementsSuspended() ? "0" : "1"));
            b.line("#" + an.id() + " [" + statusFr(an.status()) + "][" + (an.visibility() == AnnouncementVisibility.PUBLIC ? "Pub" : "Int") + "] " + clip(an.title(), 32) + " — " + an.companyName() + " (" + an.authorName() + ")",
                    an.status().equals("ACTIVE") ? "n" : "m", btns.toArray(new Btn[0]));
        }
        b.field("f_reason", "Motif (obligatoire)", "", 120);
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmAudit(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_audit", "Journal administratif", args);
        List<AdminManager.AuditRow> list = eco.admin.audit(a, 40, 0).join();
        if (list.isEmpty()) b.m("Journal vide.");
        for (AdminManager.AuditRow r : list) b.n(date(r.createdAt()) + " " + r.actor() + " · " + r.action() + " " + clip(r.target(), 18) + " " + clip(r.details(), 60));
        b.button(ViewBuilder.nav("Retour", "admin"));
        return b;
    }

    private ViewBuilder vAdmSettings(Actor a, Map<String, String> args) {
        ViewBuilder b = vb("adm_settings", "Paramètres généraux", args);
        String zoneId = eco.admin.timeZone().join();
        b.n("Fuseau horaire (périodes fiscales, affichage) : " + zoneId);
        b.n("Fréquence des taxes : " + periodText(eco.tax.periodMinutes().join()) + " (menu Taxes)");
        b.m("Les salaires sont versés une fois par période fiscale hebdomadaire ; une paie ne peut jamais être versée deux fois.");
        b.m("Sauvegardes automatiques de la base : dossier citizeneconomy/backups du monde.");
        b.field("f_zone", "Fuseau horaire (ex : Europe/Paris)", zoneId, 40).field("f_backup", "Sauvegarde automatique toutes les N minutes", "", 6);
        b.button(ViewBuilder.nav("Retour", "admin")).button(b.act("Appliquer le fuseau", "adm.zone")).button(b.act("Appliquer sauvegarde", "adm.backup.interval"))
                .button(b.act("Sauvegarder maintenant", "adm.backup.now")).button(b.act("Vérifier les soldes", "adm.verify")).button(ViewBuilder.nav("Limites d'annonces", "adm_limits"));
        return b;
    }

    private ViewBuilder vAdmLimits(Actor a, Map<String, String> args) {
        AnnouncementManager.Limits l = eco.announcements.limits().join();
        ViewBuilder b = vb("adm_limits", "Limites des annonces", args);
        b.m("Modifiables à chaud, sans redémarrage.");
        b.field("f_l_title", "Longueur max du titre (10-100)", Integer.toString(l.maxTitle()), 4).field("f_l_body", "Longueur max de la description (50-2000)", Integer.toString(l.maxBody()), 5)
                .field("f_l_hour", "Annonces max par heure et par joueur (1-60)", Integer.toString(l.perHour()), 3).field("f_l_cool", "Délai entre deux annonces, secondes (0-600)", Integer.toString(l.cooldownSeconds()), 4)
                .field("f_l_active", "Annonces actives max par entreprise (1-500)", Integer.toString(l.maxActivePerCompany()), 4);
        b.button(ViewBuilder.nav("Retour", "adm_settings")).button(b.act("Enregistrer", "adm.limits"));
        return b;
    }

    // ================================================================== caisse, stock, comptabilite

    private final Map<String, Map<Long, Long>> carts = new ConcurrentHashMap<>();

    private Map<Long, Long> cart(Actor a, long cid) {
        if (carts.size() > 500) carts.clear(); // garde-fou memoire : un panier perdu se refait en quelques secondes
        return carts.computeIfAbsent(a.id() + ":" + cid, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private static boolean canRead(Actor a, Member me) {
        return can(a, me, Permission.CASHIER) || can(a, me, Permission.MANAGE_STOCK) || can(a, me, Permission.VIEW_ACCOUNTING);
    }

    /** « 25 », « 12,5 » ou « 25 % » -> points de base ; vide = -1 (prix fixe). */
    private static long marginBp(String s) {
        String t = s == null ? "" : s.replace("%", "").replace(',', '.').replaceAll("[\\s\\u00a0]", "");
        if (t.isEmpty()) return -1;
        if (!t.matches("\\d{1,5}(\\.\\d{1,2})?")) throw new EconomyException("BAD_ARG", "Marge : un pourcentage est attendu (ex : 25 ou 12,5).");
        return Math.round(Double.parseDouble(t) * 100);
    }

    private static String marginText(long bp) { return bp < 0 ? "" : pct((int) bp).replace(" %", "").replace(",00", ""); }

    private static long qty(String s, String name, boolean allowZero) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() && allowZero) return 0;
        if (!t.matches("\\d{1,7}")) throw new EconomyException("BAD_ARG", name + " : nombre entier attendu.");
        long v = Long.parseLong(t);
        if (v == 0 && !allowZero) throw new EconomyException("BAD_ARG", name + " : doit être supérieur à 0.");
        return v;
    }

    private ViewBuilder vPos(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("pos", "Caisse — " + c.name(), args);
        if (!can(a, me, Permission.CASHIER)) b.r("Vous n'avez pas le droit de tenir la caisse (lecture seule).");
        List<StockManager.Product> products = eco.stock.list(a, cid, false).join();
        Map<Long, StockManager.Product> byId = new HashMap<>();
        for (StockManager.Product p : products) byId.put(p.id(), p);
        Map<Long, Long> cart = cart(a, cid);
        long total = 0;
        b.h("Panier");
        List<Map.Entry<Long, Long>> entries;
        synchronized (cart) { entries = new ArrayList<>(cart.entrySet()); }
        if (entries.isEmpty()) b.m("Panier vide : touchez un produit ci-dessous.");
        for (Map.Entry<Long, Long> e : entries) {
            StockManager.Product p = byId.get(e.getKey());
            if (p == null) continue;
            long line = e.getValue() * p.price();
            total += line;
            String pr = Long.toString(p.id());
            b.n(e.getValue() + " × " + p.name() + " = " + money(line), b.act("−1", "pos.sub", "pr", pr), b.act("Retirer", "pos.del", "pr", pr));
        }
        b.g("Total : " + money(total));
        if (can(a, me, Permission.CASHIER)) {
            b.field("f_customer", hibou.available() ? "Client : n° de hibou ou @matricule (vide = vente au comptoir)" : "Client : pseudo ou @matricule (vide = vente au comptoir)", "", 32);
            b.m("Vente au comptoir : encaissée tout de suite. Avec un client : une facture lui est envoyée, le stock est réservé et revient si elle est refusée.");
        }
        b.h("Produits");
        if (products.isEmpty()) b.m("Aucun produit : créez-en dans Stock.");
        int shown = 0;
        for (StockManager.Product p : products) {
            if (shown++ >= 90) { b.m("… liste tronquée : utilisez le stock pour désactiver les produits inutiles."); break; }
            String pr = Long.toString(p.id());
            String label = p.name() + " — " + money(p.price()) + " · stock " + p.stock() + (p.low() ? " (bas)" : "");
            if (p.stock() <= 0 || !can(a, me, Permission.CASHIER)) b.line(label + (p.stock() <= 0 ? " · épuisé" : ""), p.stock() <= 0 ? "r" : "n");
            else b.line(label, p.low() ? "r" : "n", b.act("+1", "pos.add", "pr", pr, "q", "1"), b.act("+5", "pos.add", "pr", pr, "q", "5"));
        }
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid)));
        if (can(a, me, Permission.CASHIER)) b.button(b.act("Encaisser", "pos.checkout")).button(ViewBuilder.confirm(b.act("Vider le panier", "pos.clear"), "Vider le panier ?"));
        b.button(ViewBuilder.nav("Ventes", "sales", "id", Long.toString(cid)));
        if (can(a, me, Permission.MANAGE_STOCK) || canRead(a, me)) b.button(ViewBuilder.nav("Stock", "stock", "id", Long.toString(cid)));
        return b;
    }

    private ViewBuilder vStock(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("stock", "Stock — " + c.name(), args);
        List<StockManager.Product> products = eco.stock.list(a, cid, true).join();
        long value = 0; int low = 0;
        for (StockManager.Product p : products) { if (p.active()) { value += p.value(); if (p.low()) low++; } }
        b.g(products.size() + " produit(s) · valeur du stock : " + money(value) + (low > 0 ? " · " + low + " en alerte" : ""));
        if (products.isEmpty()) b.m("Aucun produit.");
        int shown = 0;
        for (StockManager.Product p : products) {
            if (shown++ >= 120) { b.m("… liste tronquée."); break; }
            b.line((p.low() && p.active() ? "[BAS] " : "") + p.name() + " (" + p.sku() + ") — prix " + money(p.price()) + " · coût " + money(p.cost()) + " · stock " + p.stock()
                            + (p.minStock() > 0 ? " (seuil " + p.minStock() + ")" : "") + (p.active() ? "" : " · retiré de la vente"),
                    !p.active() ? "m" : p.low() ? "r" : "n", ViewBuilder.nav("Gérer", "stock_item", "id", Long.toString(cid), "pr", Long.toString(p.id())));
        }
        if (can(a, me, Permission.MANAGE_STOCK)) {
            b.h("Nouveau produit");
            b.field("f_sku", "Référence (facultatif)", "", 16).field("f_name", "Nom", "", 40).field("f_price", "Prix de vente (PO)", "", 12)
                    .field("f_cost", "Coût d'achat unitaire (PO)", "", 12).field("f_margin", "Marge en % sur le coût (vide = prix fixe saisi ci-dessus)", "", 7)
                    .field("f_buy", "Prix de rachat aux joueurs, PO par unité (0 = pas de rachat)", "", 12).field("f_qty", "Quantité en stock", "", 7).field("f_min", "Seuil d'alerte", "", 7);
            b.button(b.act("Créer le produit", "stock.create"));
        }
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid))).button(ViewBuilder.nav("Caisse", "pos", "id", Long.toString(cid)));
        return b;
    }

    private ViewBuilder vStockItem(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        long pid = lng(args, "pr");
        Member me = self(a, cid);
        StockManager.Product p = eco.stock.get(a, pid).join();
        if (p.companyId() != cid) throw new EconomyException("NOT_FOUND", "Produit introuvable.");
        ViewBuilder b = vb("stock_item", p.name(), args);
        b.g(p.name() + " (" + p.sku() + ")" + (p.active() ? "" : " — retiré de la vente"));
        b.n("Prix de vente " + money(p.price()) + " · coût moyen " + money(p.cost()) + " · marge " + money(p.price() - p.cost())
                + (p.marginBp() >= 0 ? " (fixée à " + marginText(p.marginBp()) + " % : le prix suit le coût)" : " (prix fixe)"));
        b.n(p.buyback() > 0 ? "Rachat aux joueurs : " + money(p.buyback()) + " l'unité" : "Pas de rachat aux joueurs.");
        b.n("Stock : " + p.stock() + " · valeur " + money(p.value()) + (p.minStock() > 0 ? " · seuil d'alerte " + p.minStock() : ""));
        b.h("Derniers mouvements");
        List<StockManager.Movement> mv = eco.stock.movements(a, pid, 12).join();
        if (mv.isEmpty()) b.m("Aucun mouvement.");
        for (StockManager.Movement m : mv)
            b.m(date(m.createdAt()) + " " + moveFr(m.reason()) + " " + (m.delta() > 0 ? "+" : "") + m.delta() + (m.actor().isEmpty() ? "" : " · " + m.actor()) + (m.note().isEmpty() ? "" : " · " + clip(m.note(), 30)));
        if (can(a, me, Permission.MANAGE_STOCK)) {
            b.field("f_name", "Nom", p.name(), 40).field("f_price", "Prix de vente fixe (PO), ignoré si une marge est indiquée", Long.toString(p.price()), 12)
                    .field("f_margin", "Marge en % sur le coût (vide = prix fixe)", marginText(p.marginBp()), 7).field("f_buy", "Prix de rachat aux joueurs, PO par unité (0 = pas de rachat)", Long.toString(p.buyback()), 12)
                    .field("f_min", "Seuil d'alerte", Long.toString(p.minStock()), 7)
                    .field("f_qty", "Quantité (réappro, perte, ou nouvel inventaire)", "", 7).field("f_cost", "Coût d'achat unitaire du réappro (vide = coût actuel)", "", 12).field("f_note", "Note", "", 60);
            b.button(b.act("Enregistrer la fiche", "stock.update")).button(b.act("Réapprovisionner", "stock.restock"))
                    .button(b.act("Inventaire (fixer le stock)", "stock.adjust")).button(ViewBuilder.confirm(b.act("Perte / casse", "stock.loss"), "Retirer cette quantité du stock sans vente ?"))
                    .button(b.act(p.active() ? "Retirer de la vente" : "Remettre en vente", "stock.toggle"));
        }
        b.button(ViewBuilder.nav("Retour", "stock", "id", Long.toString(cid)));
        return b;
    }

    private static String moveFr(String r) {
        return switch (r) {
            case "RESTOCK" -> "Entrée"; case "SALE" -> "Vente"; case "RETURN" -> "Retour"; case "ADJUST" -> "Inventaire"; case "LOSS" -> "Perte"; default -> r;
        };
    }

    private ViewBuilder vBuyback(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("buyback", "Rachat de minerais — " + c.name(), args);
        b.m("Le patron fixe le prix de rachat de chaque article. Le rachat paie le joueur depuis la banque de l'entreprise, fait entrer le stock au prix de rachat et garde une facture de rachat.");
        List<StockManager.Product> products = eco.stock.list(a, cid, false).join();
        String sel = args.get("pr");
        StockManager.Product chosen = null;
        b.h("Articles");
        if (products.isEmpty()) b.m("Aucun article : créez-les dans Stock.");
        for (StockManager.Product p : products) {
            if (sel != null && sel.equals(Long.toString(p.id()))) chosen = p;
            List<Btn> btns = new ArrayList<>();
            btns.add(ViewBuilder.nav("Choisir", "buyback", "id", Long.toString(cid), "pr", Long.toString(p.id())));
            b.line(p.name() + " — " + (p.buyback() > 0 ? "rachat " + money(p.buyback()) + " l'unité" : "pas de rachat") + " · coût moyen " + money(p.cost()) + " · prix de vente " + money(p.price())
                    + (p.marginBp() >= 0 ? " (marge " + marginText(p.marginBp()) + " %)" : "") + " · stock " + p.stock(), p.buyback() > 0 ? "n" : "m", btns.toArray(new Btn[0]));
        }
        if (chosen != null) {
            b.h("Article choisi : " + chosen.name());
            if (can(a, me, Permission.MANAGE_STOCK)) {
                b.field("f_buy", "Tarif de rachat, PO par unité (0 = arrêter le rachat)", Long.toString(chosen.buyback()), 12);
                b.field("f_seller", hibou.available() ? "Vendeur : n° de hibou" : "Vendeur : pseudo", "", 32).field("f_qty", "Quantité apportée", "", 7);
                b.button(b.act("Fixer le tarif", "buyback.price", "pr", Long.toString(chosen.id()))).button(ViewBuilder.confirm(b.act("Payer le vendeur", "buyback.pay", "pr", Long.toString(chosen.id())), "Payer le vendeur depuis la banque de l'entreprise ?"));
            } else b.m("Vous pouvez consulter, mais le droit « Gérer le stock » est nécessaire pour racheter.");
        }
        b.h("Dernières factures de rachat");
        List<BuybackManager.Buyback> list = eco.buybacks.list(a, cid, 15).join();
        if (list.isEmpty()) b.m("Aucun rachat.");
        for (BuybackManager.Buyback r : list) b.n(r.number() + " · " + date(r.createdAt()) + " · " + r.seller() + " · " + r.qty() + " × " + r.productName() + " à " + money(r.unitPrice()) + " = " + money(r.total()) + " · par " + r.by());
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid))).button(ViewBuilder.nav("Stock", "stock", "id", Long.toString(cid)));
        if (can(a, me, Permission.VIEW_ACCOUNTING)) b.button(ViewBuilder.nav("Comptabilité", "acct", "id", Long.toString(cid)));
        return b;
    }

    private ViewBuilder vSales(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        Member me = self(a, cid);
        ViewBuilder b = vb("sales", "Ventes — " + c.name(), args);
        List<SalesManager.Sale> list = eco.sales.list(a, cid, null, 30, 0).join();
        if (list.isEmpty()) b.m("Aucune vente.");
        for (SalesManager.Sale s : list) {
            String st = switch (s.status()) { case "PAID" -> "Payée"; case "PENDING" -> "En attente de paiement"; default -> "Annulée"; };
            List<Btn> btns = new ArrayList<>();
            btns.add(ViewBuilder.nav("Détail", "sale", "id", Long.toString(cid), "s", Long.toString(s.id())));
            boolean cancellable = !s.status().equals("CANCELLED") && (s.payment().equals("CASH") ? can(a, me, Permission.MANAGE_FINANCES) : s.status().equals("PENDING") && can(a, me, Permission.CASHIER));
            if (cancellable) btns.add(ViewBuilder.confirm(b.act("Annuler", "sale.cancel", "s", Long.toString(s.id())), "Annuler cette vente et remettre les articles en stock ?"));
            b.line(s.number() + " · " + date(s.createdAt()) + " · " + s.customer() + " · " + money(s.total()) + " · " + st + " · " + s.cashier(),
                    s.status().equals("PAID") ? "g" : s.status().equals("PENDING") ? "n" : "m", btns.toArray(new Btn[0]));
        }
        b.button(ViewBuilder.nav("Retour", "company", "id", Long.toString(cid))).button(ViewBuilder.nav("Caisse", "pos", "id", Long.toString(cid)));
        return b;
    }

    private ViewBuilder vSale(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        long sid = lng(args, "s");
        ViewBuilder b = vb("sale", "Détail de la vente", args);
        SalesManager.Sale sale = eco.sales.list(a, cid, null, 100, 0).join().stream().filter(s -> s.id() == sid).findFirst()
                .orElseThrow(() -> new EconomyException("NOT_FOUND", "Vente introuvable (trop ancienne pour cet écran)."));
        b.g(sale.number() + " — " + sale.customer() + " — " + money(sale.total()));
        b.m(date(sale.createdAt()) + " · caissier : " + sale.cashier() + " · " + (sale.payment().equals("CASH") ? "au comptoir" : "par facture"));
        for (SalesManager.Line l : eco.sales.lines(a, sid).join()) b.n(l.qty() + " × " + l.name() + " à " + money(l.unitPrice()) + " = " + money(l.total()));
        if (can(a, self(a, cid), Permission.VIEW_ACCOUNTING)) b.m("Coût : " + money(sale.costTotal()) + " · marge : " + money(sale.margin()));
        b.button(ViewBuilder.nav("Retour", "sales", "id", Long.toString(cid)));
        return b;
    }

    private long[] period(String per) {
        long now = clock.getAsLong();
        long start = switch (per == null ? "week" : per) {
            case "day" -> java.time.LocalDate.ofInstant(Instant.ofEpochMilli(now), zone()).atStartOfDay(zone()).toInstant().toEpochMilli();
            case "month" -> now - 30 * DAY;
            case "all" -> 0;
            default -> now - 7 * DAY;
        };
        return new long[]{start, "all".equals(per) ? Long.MAX_VALUE : now + 1};
    }

    private static String periodFr(String per) {
        return switch (per == null ? "week" : per) { case "day" -> "aujourd'hui"; case "month" -> "30 derniers jours"; case "all" -> "depuis toujours"; default -> "7 derniers jours"; };
    }

    private ViewBuilder vAcct(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        Company c = eco.companies.get(a, cid).join();
        String per = args.getOrDefault("per", "week");
        long[] range = period(per);
        AccountingManager.Report r = eco.accounting.report(a, cid, range[0], range[1]).join();
        ViewBuilder b = vb("acct", "Comptabilité — " + c.name(), args);
        b.m("Période : " + periodFr(per) + ". Calculée depuis la banque, les ventes et le stock : elle ne peut pas contredire le solde réel.");
        b.h("Compte de résultat");
        b.n("Ventes (" + r.salesCount() + ") : " + money(r.salesRevenue()));
        b.n("Autres factures encaissées : " + money(r.otherRevenue()));
        b.n("Produits saisis à la main : " + money(r.manualIncome()));
        b.g("Total des produits : " + money(r.revenue()));
        b.n("Coût des marchandises vendues : " + money(r.costOfGoods()));
        b.n("Salaires : " + money(r.salaries()));
        b.n("Impôts : " + money(r.taxes()));
        b.n("Factures payées (achats, services) : " + money(r.invoicesPaid()));
        b.n("Charges saisies à la main : " + money(r.manualExpenses()));
        b.r("Total des charges : " + money(r.expenses()));
        b.line("Résultat : " + money(r.result()) + " · marge brute sur les ventes : " + money(r.grossMargin()), r.result() >= 0 ? "g" : "r");
        b.h("Trésorerie (banque de l'entreprise)");
        b.n("Solde actuel : " + money(r.bankBalance()));
        b.n("Entrées : " + money(r.bankIn()) + " · sorties : " + money(r.bankOut()));
        b.n("Dont rachats de marchandise aux joueurs : " + money(r.buybackTotal()) + " (entrent en stock, ne sont pas une charge)");
        b.h("Bilan simplifié (à ce jour)");
        b.n("Actif — banque " + money(r.bankBalance()) + " · stock " + money(r.stockValue()) + " · stock réservé " + money(r.reservedStock()) + " · factures à encaisser " + money(r.receivables()));
        b.n("Passif — impôts à payer : " + money(r.liabilities()));
        b.g("Capitaux propres : " + money(r.equity()));
        b.h("Meilleurs produits");
        List<AccountingManager.TopProduct> top = eco.accounting.topProducts(a, cid, range[0], range[1], 5).join();
        if (top.isEmpty()) b.m("Aucune vente sur la période.");
        for (AccountingManager.TopProduct t : top) b.n(t.name() + " : " + t.qty() + " vendu(s) · " + money(t.revenue()) + " · marge " + money(t.margin()));
        String ids = Long.toString(cid);
        b.button(ViewBuilder.nav("Retour", "company", "id", ids)).button(ViewBuilder.nav("Journal", "acct_journal", "id", ids, "per", per))
                .button(ViewBuilder.nav("Écritures manuelles", "acct_manual", "id", ids)).button(ViewBuilder.nav("Ventes", "sales", "id", ids))
                .button(ViewBuilder.nav("Rachats et tarifs", "buyback", "id", ids))
                .button(ViewBuilder.nav("Aujourd'hui", "acct", "id", ids, "per", "day")).button(ViewBuilder.nav("7 jours", "acct", "id", ids, "per", "week"))
                .button(ViewBuilder.nav("30 jours", "acct", "id", ids, "per", "month")).button(ViewBuilder.nav("Tout", "acct", "id", ids, "per", "all"));
        return b;
    }

    private ViewBuilder vAcctJournal(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        String per = args.getOrDefault("per", "week");
        long[] range = period(per);
        ViewBuilder b = vb("acct_journal", "Journal — " + periodFr(per), args);
        List<AccountingManager.JournalEntry> list = eco.accounting.journal(a, cid, range[0], range[1], 100).join();
        if (list.isEmpty()) b.m("Aucune écriture sur la période.");
        for (AccountingManager.JournalEntry e : list) b.n(date(e.at()) + " · " + clip(e.label(), 56) + "\n    Débit " + e.debit() + " / Crédit " + e.credit() + " : " + money(e.amount()));
        b.button(ViewBuilder.nav("Retour", "acct", "id", Long.toString(cid), "per", per));
        return b;
    }

    private ViewBuilder vAcctManual(Actor a, Map<String, String> args) {
        long cid = lng(args, "id");
        ViewBuilder b = vb("acct_manual", "Écritures manuelles", args);
        b.m("Pour ce que le jeu ne voit pas (loyer, aide, prime…). Une écriture manuelle n'ajoute ni ne retire d'argent en banque : elle ne sert qu'à la comptabilité.");
        List<AccountingManager.Manual> list = eco.accounting.manualEntries(a, cid, 30).join();
        if (list.isEmpty()) b.m("Aucune écriture.");
        for (AccountingManager.Manual m : list)
            b.line(date(m.entryAt()) + " · " + (m.kind().equals("INCOME") ? "Produit" : "Charge") + " " + m.category() + " · " + clip(m.label(), 40) + " · " + money(m.amount()) + " · " + m.createdBy(),
                    m.kind().equals("INCOME") ? "g" : "r", ViewBuilder.confirm(b.act("Supprimer", "acct.manual.del", "i", Long.toString(m.id())), "Supprimer cette écriture ?"));
        b.field("f_cat", "Catégorie (ex : Loyer, Aide)", "", 24).field("f_label", "Libellé", "", 80).field("f_amount", "Montant (PO)", "", 14);
        b.button(ViewBuilder.nav("Retour", "acct", "id", Long.toString(cid))).button(b.act("Ajouter un produit", "acct.manual", "kind", "income")).button(b.act("Ajouter une charge", "acct.manual", "kind", "expense"));
        return b;
    }

    /** Client d'une vente : vide = comptoir ; @matricule ou @nom = entreprise ; sinon joueur. */
    private Object[] saleCustomer(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return new Object[]{null, null};
        if (t.startsWith("@")) { Company co = companyByName(t.substring(1)); return new Object[]{AccountRef.business(co.id()), co.name()}; }
        PlayerManager.PlayerRef p = invoiceRecipient(t);
        return new Object[]{AccountRef.personal(p.uuid()), p.name()};
    }

    private String saleAction(Actor a, String action, Map<String, String> g) {
        long cid = lng(g, "id");
        Map<Long, Long> cart = cart(a, cid);
        switch (action) {
            case "pos.add" -> {
                long pr = lng(g, "pr");
                long q = qty(g.getOrDefault("q", "1"), "Quantité", false);
                StockManager.Product p = eco.stock.get(a, pr).join();
                if (p.companyId() != cid || !p.active()) throw new EconomyException("NOT_FOUND", "Produit introuvable.");
                synchronized (cart) {
                    long now = cart.getOrDefault(pr, 0L) + q;
                    if (now > p.stock()) throw new EconomyException("OUT_OF_STOCK", "Stock insuffisant pour « " + p.name() + " » : " + p.stock() + " disponible(s).");
                    if (cart.size() >= SalesManager.MAX_LINES && !cart.containsKey(pr)) throw new EconomyException("CART_LIMIT", "Trop de lignes dans le panier.");
                    cart.put(pr, now);
                }
                return "";
            }
            case "pos.sub" -> { long pr = lng(g, "pr"); synchronized (cart) { long v = cart.getOrDefault(pr, 0L) - 1; if (v <= 0) cart.remove(pr); else cart.put(pr, v); } return ""; }
            case "pos.del" -> { synchronized (cart) { cart.remove(lng(g, "pr")); } return ""; }
            case "pos.clear" -> { cart.clear(); return "Panier vidé."; }
            case "pos.checkout" -> {
                List<SalesManager.Item> items;
                synchronized (cart) { items = cart.entrySet().stream().map(e -> new SalesManager.Item(e.getKey(), e.getValue())).toList(); }
                Object[] cust = saleCustomer(arg(g, "f_customer"));
                long id = eco.sales.checkout(a, cid, items, (AccountRef) cust[0], (String) cust[1]).join();
                cart.clear();
                SalesManager.Sale s = eco.sales.list(a, cid, null, 5, 0).join().stream().filter(x -> x.id() == id).findFirst().orElse(null);
                return s == null ? "Vente enregistrée." : s.payment().equals("CASH")
                        ? "Vente " + s.number() + " encaissée : " + money(s.total()) + "."
                        : "Vente " + s.number() + " : facture de " + money(s.total()) + " envoyée à " + s.customer() + ".";
            }
            default -> throw new EconomyException("BAD_ACTION", "Action inconnue.");
        }
    }

    private String stockAction(Actor a, String action, Map<String, String> g) {
        long cid = lng(g, "id");
        switch (action) {
            case "stock.create" -> {
                eco.stock.create(a, cid, arg(g, "f_sku"), arg(g, "f_name"), amountOrZero(arg(g, "f_price")), amountOrZero(arg(g, "f_cost")),
                        qty(arg(g, "f_qty"), "Quantité", true), qty(arg(g, "f_min"), "Seuil", true), marginBp(arg(g, "f_margin")), amountOrZero(arg(g, "f_buy"))).join();
                return "Produit créé.";
            }
            case "stock.update" -> {
                eco.stock.update(a, lng(g, "pr"), arg(g, "f_name"), amountOrZero(arg(g, "f_price")), qty(arg(g, "f_min"), "Seuil", true), true,
                        marginBp(arg(g, "f_margin")), amountOrZero(arg(g, "f_buy"))).join();
                return "Fiche enregistrée.";
            }
            case "stock.toggle" -> {
                StockManager.Product p = eco.stock.get(a, lng(g, "pr")).join();
                eco.stock.update(a, p.id(), p.name(), p.price(), p.minStock(), !p.active()).join();
                return p.active() ? "Produit retiré de la vente." : "Produit remis en vente.";
            }
            case "stock.restock" -> {
                String cost = arg(g, "f_cost");
                eco.stock.restock(a, lng(g, "pr"), qty(arg(g, "f_qty"), "Quantité", false), cost.isEmpty() ? -1 : amountOrZero(cost), arg(g, "f_note")).join();
                return "Stock réapprovisionné (coût moyen recalculé).";
            }
            case "stock.adjust" -> { eco.stock.adjust(a, lng(g, "pr"), qty(arg(g, "f_qty"), "Quantité", true), arg(g, "f_note")).join(); return "Inventaire enregistré."; }
            case "stock.loss" -> { eco.stock.loss(a, lng(g, "pr"), qty(arg(g, "f_qty"), "Quantité", false), arg(g, "f_note")).join(); return "Perte enregistrée."; }
            default -> throw new EconomyException("BAD_ACTION", "Action inconnue.");
        }
    }

    // ================================================================== actions

    private String perform(Actor a, String action, Map<String, String> g) {
        if (action.startsWith("adm.") && !action.startsWith("adm.census.")) requireAdmin(a); // le recensement est controle par le service (employes de la couronne)
        return switch (action) {
            // --- compte personnel
            case "pos.add", "pos.sub", "pos.del", "pos.clear", "pos.checkout" -> saleAction(a, action, g);
            case "stock.create", "stock.update", "stock.toggle", "stock.restock", "stock.adjust", "stock.loss" -> stockAction(a, action, g);
            case "buyback.price" -> {
                StockManager.Product p = eco.stock.get(a, lng(g, "pr")).join();
                eco.stock.update(a, p.id(), p.name(), p.price(), p.minStock(), p.active(), p.marginBp(), amountOrZero(arg(g, "f_buy"))).join();
                yield "Tarif de rachat enregistré.";
            }
            case "buyback.pay" -> {
                PlayerManager.PlayerRef seller = invoiceRecipient(arg(g, "f_seller"));
                long id = eco.buybacks.buy(a, lng(g, "pr"), seller.uuid(), qty(arg(g, "f_qty"), "Quantité", false)).join();
                BuybackManager.Buyback r = eco.buybacks.list(a, lng(g, "id"), 5).join().stream().filter(x -> x.id() == id).findFirst().orElse(null);
                yield r == null ? "Rachat enregistré." : "Facture de rachat " + r.number() + " : " + r.qty() + " × " + r.productName() + ", " + money(r.total()) + " versés à " + r.seller() + ".";
            }
            case "sale.cancel" -> { eco.sales.cancel(a, lng(g, "s")).join(); yield "Vente annulée, articles remis en stock."; }
            case "acct.manual" -> {
                eco.accounting.addManual(a, lng(g, "id"), arg(g, "kind").equals("income"), arg(g, "f_cat"), arg(g, "f_label"), amount(arg(g, "f_amount"))).join();
                yield "Écriture ajoutée.";
            }
            case "acct.manual.del" -> { eco.accounting.deleteManual(a, lng(g, "i")).join(); yield "Écriture supprimée."; }
            case "bank.deposit" -> cashDeposit(a, g);
            case "bank.withdraw" -> cashWithdraw(a, g);
            case "bank.transfer" -> {
                long amt = amount(arg(g, "f_amount"));
                UUID to = player(arg(g, "f_target")).uuid();
                TxResult r = eco.bank.transfer(a, txId(a, g, action), to, amt, arg(g, "f_desc")).join();
                yield r.replayed() ? "Virement déjà enregistré." : "Virement de " + money(amt) + " effectué.";
            }
            case "adm.staff.hire" -> {
                PlayerManager.PlayerRef p = player(arg(g, "f_player"));
                eco.staff.hire(a, p.uuid(), p.name(), arg(g, "f_title"), amountOrZero(arg(g, "f_salary"))).join();
                yield p.name() + " est maintenant employé de la couronne.";
            }
            case "adm.staff.set" -> { eco.staff.update(a, uuid(g, "p"), arg(g, "f_title"), arg(g, "f_salary").isBlank() ? -1 : amountOrZero(arg(g, "f_salary"))).join(); yield "Employé de la couronne modifié (salaire à la prochaine paie)."; }
            case "adm.staff.fire" -> { eco.staff.fire(a, uuid(g, "p")).join(); yield "Employé renvoyé de la couronne."; }
            case "adm.co.emblem" -> { eco.companies.setEmblem(a, lng(g, "id"), arg(g, "f_emblem")).join(); yield "Emblème modifié."; }
            case "adm.census.save" -> { eco.census.adminSet(a, uuid(g, "p"), arg(g, "f_first"), arg(g, "f_last"), arg(g, "f_age"), arg(g, "f_prof")).join(); yield "Fiche enregistrée."; }
            case "adm.census.unlock" -> { eco.census.adminUnlock(a, uuid(g, "p")).join(); yield "Fiche rouverte : le joueur peut la corriger."; }
            case "adm.census.validate" -> { eco.census.adminValidate(a, uuid(g, "p")).join(); yield "Fiche validée."; }
            case "adm.census.delete" -> { eco.census.adminDelete(a, uuid(g, "p")).join(); yield "Recensement effacé."; }
            case "notif.readall" -> { eco.notifications.markAllRead(a.id()).join(); yield "Notifications marquées comme lues."; }

            // --- demandes de creation
            case "req.submit" -> {
                eco.companies.submitRequest(a, arg(g, "f_name"), arg(g, "f_desc"), arg(g, "f_sector"), arg(g, "f_emblem")).join();
                yield "Demande envoyée : elle sera examinée par l'administration.";
            }
            case "req.update" -> {
                eco.companies.updateRequest(a, lng(g, "r"), arg(g, "f_name"), arg(g, "f_desc"), arg(g, "f_sector"), arg(g, "f_emblem")).join();
                yield "Demande corrigée et renvoyée.";
            }

            // --- employes
            case "emp.hire" -> {
                PlayerManager.PlayerRef p = player(arg(g, "f_player"));
                eco.employees.hire(a, lng(g, "id"), p.uuid(), p.name(), amountOrZero(arg(g, "f_salary"))).join();
                yield p.name() + " a été recruté.";
            }
            case "emp.fire" -> { eco.employees.fire(a, lng(g, "id"), uuid(g, "p")).join(); yield "Employé licencié."; }
            case "emp.salary" -> {
                eco.employees.setSalary(a, lng(g, "id"), uuid(g, "p"), amountOrZero(arg(g, "f_salary"))).join();
                yield "Salaire modifié (effet à la prochaine paie).";
            }
            case "emp.grade" -> { eco.employees.setGrade(a, lng(g, "id"), uuid(g, "p"), arg(g, "f_grade")).join(); yield "Grade modifié."; }
            case "emp.perm" -> {
                long cid = lng(g, "id");
                UUID p = uuid(g, "p");
                Permission perm;
                try { perm = Permission.valueOf(arg(g, "perm")); } catch (IllegalArgumentException e) { throw new EconomyException("BAD_ARG", "Permission inconnue."); }
                Member t = eco.employees.members(a, cid).join().stream().filter(m -> m.player().equals(p)).findFirst()
                        .orElseThrow(() -> new EconomyException("TARGET_UNKNOWN", "Membre introuvable."));
                eco.employees.setPermissions(a, cid, p, t.permissions() ^ perm.bit()).join();
                yield "Permission mise à jour.";
            }
            case "emp.promote" -> { eco.employees.promoteToManager(a, lng(g, "id"), uuid(g, "p")).join(); yield "Gérant nommé."; }
            case "emp.demote" -> { eco.employees.demoteManager(a, lng(g, "id")).join(); yield "Gérant retiré."; }

            // --- caisse de l'entreprise
            case "cbank.deposit" -> {
                long amt = amount(arg(g, "f_amount"));
                TxResult r = eco.bank.businessDeposit(a, lng(g, "id"), txId(a, g, action), amt).join();
                yield r.replayed() ? "Opération déjà enregistrée." : "Apport de " + money(amt) + " versé à la caisse.";
            }
            case "cbank.withdraw" -> {
                long amt = amount(arg(g, "f_amount"));
                TxResult r = eco.bank.businessWithdraw(a, lng(g, "id"), txId(a, g, action), amt).join();
                yield r.replayed() ? "Opération déjà enregistrée." : "Retrait de " + money(amt) + " vers votre compte.";
            }
            case "cbank.pay" -> {
                long amt = amount(arg(g, "f_amount"));
                TxResult r = eco.bank.businessPay(a, lng(g, "id"), recipient(arg(g, "f_target")), txId(a, g, action), amt, arg(g, "f_desc")).join();
                yield r.replayed() ? "Paiement déjà enregistré." : "Paiement de " + money(amt) + " effectué.";
            }

            // --- annonces
            case "ann.publish" -> {
                AnnouncementVisibility vis = arg(g, "vis").equals("INTERNAL") ? AnnouncementVisibility.INTERNAL : AnnouncementVisibility.PUBLIC;
                long cid = lng(g, "id");
                AnnouncementCategory cat = category(arg(g, "f_cat"));
                eco.announcements.publish(a, cid, arg(g, "f_title"), arg(g, "f_body"), cat, vis, expiryFromDays(arg(g, "f_days"))).join();
                if (vis == AnnouncementVisibility.PUBLIC) {
                    try { broadcaster.publicAnnouncement(eco.companies.get(a, cid).join().name(), categoryFr(cat), clip(arg(g, "f_title").trim(), 60), clip(arg(g, "f_body").trim(), 400)); }
                    catch (RuntimeException ignored) { /* la diffusion ne doit jamais faire échouer la publication */ }
                }
                yield "Annonce publiée.";
            }
            case "ann.edit" -> {
                long aid = lng(g, "a");
                Announcement old = eco.announcements.companyBoard(a, lng(g, "id"), 200, 0).join().stream().filter(x -> x.id() == aid).findFirst()
                        .orElseThrow(() -> new EconomyException("TARGET_UNKNOWN", "Annonce introuvable."));
                String title = arg(g, "f_title").isEmpty() ? old.title() : arg(g, "f_title");
                String body = arg(g, "f_body").isEmpty() ? old.body() : arg(g, "f_body");
                AnnouncementCategory cat = arg(g, "f_cat").isEmpty() ? old.category() : category(arg(g, "f_cat"));
                Long exp = arg(g, "f_days").isEmpty() ? old.expiresAt() : expiryFromDays(arg(g, "f_days"));
                eco.announcements.edit(a, aid, title, body, cat, old.visibility(), exp).join();
                yield "Annonce modifiée.";
            }
            case "ann.delete" -> { eco.announcements.delete(a, lng(g, "a")).join(); yield "Annonce supprimée."; }

            // --- factures
            case "inv.create" -> {
                AccountRef to = arg(g, "kind").equals("company") ? AccountRef.business(companyByName(arg(g, "f_target")).id()) : AccountRef.personal(invoiceRecipient(arg(g, "f_target")).uuid());
                eco.invoices.create(a, lng(g, "id"), to, arg(g, "f_desc"), amount(arg(g, "f_amount")), expiryFromDays(arg(g, "f_due"))).join();
                yield "Facture émise.";
            }
            case "inv.pay" -> { eco.invoices.pay(a, lng(g, "i")).join(); yield "Facture payée."; }
            case "inv.refuse" -> { eco.invoices.refuse(a, lng(g, "i")).join(); yield "Facture refusée."; }
            case "inv.cancel" -> { eco.invoices.cancel(a, lng(g, "i")).join(); yield "Facture annulée."; }

            // --- impots
            case "tax.pay" -> { eco.tax.pay(a, lng(g, "d")).join(); yield "Impôt payé au royaume."; }

            // --- administration : demandes et entreprises
            case "adm.req.accept" -> { eco.companies.accept(a, lng(g, "r"), arg(g, "f_note")).join(); yield "Entreprise créée et activée."; }
            case "adm.req.refuse" -> { eco.companies.refuse(a, lng(g, "r"), arg(g, "f_note")).join(); yield "Demande refusée."; }
            case "adm.req.changes" -> { eco.companies.requestChanges(a, lng(g, "r"), arg(g, "f_note")).join(); yield "Modifications demandées au joueur."; }
            case "adm.co.suspend" -> { eco.companies.setStatus(a, lng(g, "id"), CompanyStatus.SUSPENDED, arg(g, "f_reason")).join(); yield "Entreprise suspendue."; }
            case "adm.co.activate" -> { eco.companies.setStatus(a, lng(g, "id"), CompanyStatus.ACTIVE, arg(g, "f_reason").isEmpty() ? "Réactivation" : arg(g, "f_reason")).join(); yield "Entreprise réactivée."; }
            case "adm.co.close" -> { eco.companies.setStatus(a, lng(g, "id"), CompanyStatus.CLOSED, arg(g, "f_reason")).join(); yield "Entreprise fermée."; }
            case "adm.owner.transfer" -> {
                eco.admin.transferOwnership(a, lng(g, "id"), player(arg(g, "f_player")).uuid(), arg(g, "f_reason")).join();
                yield "Propriété transférée.";
            }
            case "adm.emp.demote" -> { eco.employees.demoteManager(a, lng(g, "id")).join(); yield "Gérant retiré."; }
            case "adm.emp.fire" -> { eco.employees.fire(a, lng(g, "id"), uuid(g, "p")).join(); yield "Employé licencié."; }
            case "adm.ann.remove" -> { eco.announcements.adminRemove(a, lng(g, "a"), arg(g, "f_reason")).join(); yield "Annonce retirée."; }
            case "adm.ann.pub" -> {
                boolean off = arg(g, "on").equals("1");
                eco.announcements.setPublicationsSuspended(a, lng(g, "id"), off, arg(g, "f_reason").isEmpty() ? "Modération" : arg(g, "f_reason")).join();
                yield off ? "Publication suspendue pour cette entreprise." : "Publication de nouveau autorisée.";
            }

            // --- administration : taxes
            case "adm.tax.rate" -> {
                TaxKind k = kind(g);
                TaxRate cur = latest(a, k);
                int bp = rateBp(arg(g, "f_rate"));
                eco.tax.setTax(a, k, cur == null ? defaultBase(k) : cur.base(), bp, cur == null || cur.enabled(), cur == null ? TaxMode.DECLARATION : cur.mode()).join();
                yield taxFr(k) + " : " + pct(bp) + " à partir de la prochaine période.";
            }
            case "adm.tax.toggle" -> {
                TaxKind k = kind(g);
                TaxRate cur = latest(a, k);
                if (cur == null) throw new EconomyException("BAD_TAX", "Définissez d'abord un taux.");
                eco.tax.setTax(a, k, cur.base(), cur.rateBp(), !cur.enabled(), cur.mode()).join();
                yield taxFr(k) + (cur.enabled() ? " désactivée" : " activée") + " à partir de la prochaine période.";
            }
            case "adm.tax.mode" -> {
                TaxKind k = kind(g);
                TaxRate cur = latest(a, k);
                if (cur == null) throw new EconomyException("BAD_TAX", "Définissez d'abord un taux.");
                eco.tax.setTax(a, k, cur.base(), cur.rateBp(), cur.enabled(), cur.mode() == TaxMode.AUTO ? TaxMode.DECLARATION : TaxMode.AUTO).join();
                yield "Mode de collecte modifié pour la prochaine période.";
            }
            case "adm.tax.base" -> {
                TaxKind k = kind(g);
                TaxRate cur = latest(a, k);
                if (cur == null) throw new EconomyException("BAD_TAX", "Définissez d'abord un taux.");
                TaxBase nb = cur.base() == TaxBase.REVENUE ? TaxBase.PROFIT : TaxBase.REVENUE;
                eco.tax.setTax(a, k, nb, cur.rateBp(), cur.enabled(), cur.mode()).join();
                yield "Base modifiée : " + (nb == TaxBase.PROFIT ? "bénéfice" : "chiffre d'affaires") + " (prochaine période).";
            }
            case "adm.tax.ptype" -> {
                TxType t;
                try { t = TxType.valueOf(arg(g, "type")); } catch (IllegalArgumentException e) { throw new EconomyException("BAD_ARG", "Type invalide."); }
                if (!TaxManager.SELECTABLE_PERSONAL_TYPES.contains(t)) throw new EconomyException("BAD_ARG", "Type non sélectionnable.");
                Set<TxType> set = EnumSet.noneOf(TxType.class);
                set.addAll(eco.tax.personalIncomeTypes().join());
                if (!set.remove(t)) set.add(t);
                eco.tax.setPersonalIncomeTypes(a, set).join();
                yield "Revenus personnels imposables mis à jour.";
            }
            case "adm.tax.period" -> { eco.tax.setPeriodMinutes(a, intArg(arg(g, "f_period"), "Fréquence")).join(); yield "Fréquence des taxes modifiée."; }
            case "adm.tax.exempt", "adm.tax.unexempt" -> {
                boolean on = action.equals("adm.tax.exempt");
                String who = arg(g, "who");
                if (who.equals("player")) {
                    String id = player(arg(g, "f_subject")).uuid().toString();
                    eco.tax.setExemption(a, "PLAYER", id, TaxKind.PERSONAL_INCOME, on).join();
                    eco.tax.setExemption(a, "PLAYER", id, TaxKind.SALARY, on).join();
                } else if (who.equals("company")) {
                    eco.tax.setExemption(a, "COMPANY", Long.toString(companyByName(arg(g, "f_subject")).id()), TaxKind.BUSINESS_INCOME, on).join();
                } else throw new EconomyException("BAD_ARG", "Cible invalide.");
                yield on ? "Exonération accordée." : "Exonération levée.";
            }
            case "adm.tax.waive" -> { eco.tax.waive(a, lng(g, "d"), arg(g, "f_reason")).join(); yield "Déclaration exonérée."; }

            // --- administration : tresor royal
            case "adm.spend" -> royalSpend(a, g);
            case "adm.revenue" -> royalRevenue(a, g);

            // --- administration : parametres
            case "adm.limits" -> {
                eco.announcements.setLimits(a, new AnnouncementManager.Limits(intArg(arg(g, "f_l_title"), "Titre"), intArg(arg(g, "f_l_body"), "Description"),
                        intArg(arg(g, "f_l_hour"), "Par heure"), intArg(arg(g, "f_l_cool"), "Délai"), intArg(arg(g, "f_l_active"), "Actives"))).join();
                yield "Limites enregistrées (effet immédiat).";
            }
            case "adm.zone" -> { eco.admin.setTimeZone(a, arg(g, "f_zone")).join(); zoneAt = 0; yield "Fuseau horaire modifié."; }
            case "adm.backup.interval" -> { eco.admin.setBackupInterval(a, intArg(arg(g, "f_backup"), "Intervalle")).join(); yield "Intervalle de sauvegarde modifié."; }
            case "adm.backup.now" -> { eco.db.backup("manual", EconomyScheduler.BACKUP_KEEP).join(); eco.admin.logAction(a, "BACKUP", "manual").join(); yield "Sauvegarde effectuée."; }
            case "adm.verify" -> {
                List<String> problems = eco.transactions.verifyBalances().join();
                yield problems.isEmpty() ? "Tous les soldes sont cohérents avec le grand livre." : "ANOMALIES : " + clip(String.join(" ; ", problems), 300);
            }
            case "open" -> null;
            default -> throw new EconomyException("BAD_ACTION", "Action inconnue.");
        };
    }

    private TaxKind kind(Map<String, String> g) {
        try { return TaxKind.valueOf(arg(g, "kind")); } catch (IllegalArgumentException e) { throw new EconomyException("BAD_ARG", "Taxe inconnue."); }
    }

    private TaxRate latest(Actor a, TaxKind k) {
        return eco.tax.latestRates(a).join().stream().filter(r -> r.kind() == k).findFirst().orElse(null);
    }

    private static TaxBase defaultBase(TaxKind k) {
        return switch (k) { case BUSINESS_INCOME -> TaxBase.PROFIT; case PERSONAL_INCOME -> TaxBase.SELECTED_INCOME; case SALARY -> TaxBase.SALARY; };
    }

    // ------------------------------------------------------------------ argent physique

    /** Retire les pieces, credite le compte ; en cas d'echec ou de rejeu, les pieces sont rendues. */
    private String cashDeposit(Actor a, Map<String, String> g) {
        long amt = amount(arg(g, "f_amount"));
        CoinMath.Plan plan = takeCoins(a, amt);
        TxResult r;
        try { r = eco.bank.deposit(a, txId(a, g, "bank.deposit"), amt).join(); }
        catch (RuntimeException e) { returnCoins(a, plan.remove()); throw e; }
        if (r.replayed()) { returnCoins(a, plan.remove()); return "Opération déjà enregistrée."; }
        if (plan.change() > 0) returnCoins(a, Coins.breakdown(plan.change()));
        return "Dépôt de " + money(amt) + " effectué" + (plan.change() > 0 ? " (monnaie rendue : " + money(plan.change()) + ")." : ".");
    }

    private String cashWithdraw(Actor a, Map<String, String> g) {
        long amt = amount(arg(g, "f_amount"));
        TxResult r = eco.bank.withdraw(a, txId(a, g, "bank.withdraw"), amt).join();
        if (r.replayed()) return "Opération déjà enregistrée.";
        if (!giveCoins(a, Coins.breakdown(amt))) {
            eco.bank.deposit(a, UUID.randomUUID(), amt).join(); // compensation : le joueur n'est plus connecte
            throw new EconomyException("OFFLINE", "Retrait annulé : joueur indisponible.");
        }
        return "Retrait de " + money(amt) + " en pièces effectué.";
    }

    private CoinMath.Plan takeCoins(Actor a, long amt) {
        CoinMath.Plan plan = cash.onServer(() -> {
            CoinMath.Plan p = CoinMath.plan(cash.countCoins(a.id()), amt);
            if (p != null) cash.removeCoins(a.id(), p.remove());
            return p;
        });
        if (plan == null) throw new EconomyException("NO_COINS", "Vous n'avez pas assez de pièces sur vous.");
        return plan;
    }

    private void returnCoins(Actor a, long[] counts) { cash.onServer(() -> cash.giveCoins(a.id(), counts)); }

    private boolean giveCoins(Actor a, long[] counts) { return Boolean.TRUE.equals(cash.onServer(() -> cash.giveCoins(a.id(), counts))); }

    private String royalSpend(Actor a, Map<String, String> g) {
        long amt = amount(arg(g, "f_amount"));
        String reason = arg(g, "f_reason");
        UUID tx = txId(a, g, "adm.spend");
        if (arg(g, "to").equals("cash")) {
            TxResult r = eco.treasury.spend(a, tx, AccountRef.cash(), amt, reason).join();
            if (r.replayed()) return "Opération déjà enregistrée.";
            if (!giveCoins(a, Coins.breakdown(amt))) {
                eco.treasury.addRevenue(a, UUID.randomUUID(), amt, "Annulation retrait en espèces").join();
                throw new EconomyException("OFFLINE", "Retrait annulé : joueur indisponible.");
            }
            return "Retrait royal de " + money(amt) + " en pièces.";
        }
        TxResult r = eco.treasury.spend(a, tx, recipient(arg(g, "f_target")), amt, reason).join();
        return r.replayed() ? "Opération déjà enregistrée." : "Dépense royale de " + money(amt) + " effectuée.";
    }

    private String royalRevenue(Actor a, Map<String, String> g) {
        long amt = amount(arg(g, "f_amount"));
        CoinMath.Plan plan = takeCoins(a, amt);
        TxResult r;
        try { r = eco.treasury.addRevenue(a, txId(a, g, "adm.revenue"), amt, arg(g, "f_reason")).join(); }
        catch (RuntimeException e) { returnCoins(a, plan.remove()); throw e; }
        if (r.replayed()) { returnCoins(a, plan.remove()); return "Opération déjà enregistrée."; }
        if (plan.change() > 0) returnCoins(a, Coins.breakdown(plan.change()));
        return "Revenu de " + money(amt) + " versé au trésor royal.";
    }
}
