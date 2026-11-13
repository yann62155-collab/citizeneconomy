package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.PermissionManager.Authority;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Fiscalite. Principes :
 *  - taux VERSIONNES : un changement ajoute une ligne qui prend effet au debut de la periode suivante ;
 *    une periode est toujours taxee au taux en vigueur a son debut -> jamais de retroactivite ;
 *  - les declarations sont CALCULEES A LA CLOTURE depuis les transactions (pas de compteur modifiable) ;
 *  - UNIQUE(tax_id, compte, periode) + identifiants de paiement deterministes -> rejouable sans doublon ;
 *  - aucune double imposition : les transferts/depots/retraits ne sont jamais une base ; un type de revenu
 *    n'est compte que par une seule taxe.
 */
public final class TaxManager {
    /** Types de revenus personnels que l'administration peut selectionner. */
    public static final Set<TxType> SELECTABLE_PERSONAL_TYPES = EnumSet.of(TxType.SALARY, TxType.ROYAL_EXPENSE, TxType.TRANSFER);
    private static final int MAX_CATCHUP_PERIODS = 520;

    private final Database db;
    private final LongSupplier clock;

    public TaxManager(Database db) { this(db, System::currentTimeMillis); }
    public TaxManager(Database db, LongSupplier clock) { this.db = db; this.clock = clock; }

    // ------------------------------------------------------------------ configuration (admin)

    public CompletableFuture<Long> setTax(Actor admin, TaxKind kind, TaxBase base, int rateBp, boolean enabled, TaxMode mode) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            if (rateBp < 0 || rateBp > 10_000) throw new EconomyException("BAD_RATE", "Taux invalide (0 à 100 %).");
            boolean ok = switch (kind) {
                case BUSINESS_INCOME -> base == TaxBase.REVENUE || base == TaxBase.PROFIT;
                case PERSONAL_INCOME -> base == TaxBase.SELECTED_INCOME;
                case SALARY -> base == TaxBase.SALARY;
            };
            if (!ok) throw new EconomyException("BAD_TAX", "Base " + base + " incompatible avec " + kind + ".");
            long now = clock.getAsLong();
            ZoneId z = zone(c);
            int minutes = periodMinutes(c);
            long start = TaxPeriods.floorBoundary(now, minutes, z);
            long effective = TaxPeriods.nextBoundary(start, minutes, z);
            if (Settings.get(c, Settings.TAX_CLOSED_UNTIL).isEmpty())
                Settings.set(c, Settings.TAX_CLOSED_UNTIL, Long.toString(start), admin.id().toString(), now);
            Optional<TaxRate> old = effective(c, kind, effective);
            long id = Sql.insert(c, "INSERT INTO taxes(kind,base,rate_bp,enabled,mode,period_minutes,effective_from,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    kind, base, rateBp, enabled, mode, minutes, effective, admin.id().toString(), now);
            String desc = kind + " " + old.map(o -> o.rateBp() + "bp/" + (o.enabled() ? "on" : "off")).orElse("aucun")
                    + " -> " + rateBp + "bp/" + (enabled ? "on" : "off") + " " + mode + " effet=" + effective;
            Records.audit(c, admin.id(), "TAX_CHANGED", "tax", id, desc, now);
            boolean changed = old.isEmpty() || old.get().rateBp() != rateBp || old.get().enabled() != enabled;
            if (changed) {
                Sql.update(c, "INSERT INTO notifications(player_uuid,type,message,created_at) SELECT uuid, 'TAX_CHANGED', ?, ? FROM players",
                        "Modification fiscale : " + label(kind) + " a " + String.format(Locale.ROOT, "%.2f", rateBp / 100.0)
                                + " % (" + (enabled ? "active" : "désactivée") + "), applicable dès la prochaine période.", now);
            }
            return id;
        });
    }

    public CompletableFuture<Void> setPersonalIncomeTypes(Actor admin, Set<TxType> types) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            if (!SELECTABLE_PERSONAL_TYPES.containsAll(types)) throw new EconomyException("BAD_TAX", "Type de revenu non sélectionnable.");
            String csv = types.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
            long now = clock.getAsLong();
            Settings.set(c, Settings.TAX_PERSONAL_TYPES, csv, admin.id().toString(), now);
            Records.audit(c, admin.id(), "TAX_PERSONAL_TYPES", "settings", Settings.TAX_PERSONAL_TYPES, csv, now);
            return null;
        });
    }

    /** Frequence globale des prelevements (10080 = hebdomadaire, dimanche 00:00). S'applique aux periodes suivantes. */
    public CompletableFuture<Void> setPeriodMinutes(Actor admin, int minutes) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            if (minutes < 60 || minutes > 525_600) throw new EconomyException("BAD_TAX", "Période invalide (60 a 525600 minutes).");
            long now = clock.getAsLong();
            Settings.set(c, Settings.TAX_PERIOD_MINUTES, Integer.toString(minutes), admin.id().toString(), now);
            Records.audit(c, admin.id(), "TAX_PERIOD", "settings", Settings.TAX_PERIOD_MINUTES, Integer.toString(minutes), now);
            return null;
        });
    }

    public CompletableFuture<Void> setExemption(Actor admin, String subjectType, String subjectId, TaxKind kind, boolean exempt) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            if (!subjectType.equals("PLAYER") && !subjectType.equals("COMPANY")) throw new EconomyException("BAD_TAX", "Type de sujet invalide.");
            long now = clock.getAsLong();
            if (exempt) {
                Sql.update(c, "INSERT OR IGNORE INTO tax_exemptions(subject_type,subject_id,tax_kind,created_by,created_at) VALUES (?,?,?,?,?)",
                        subjectType, subjectId, kind, admin.id().toString(), now);
            } else {
                Sql.update(c, "UPDATE tax_exemptions SET revoked_at = ? WHERE subject_type=? AND subject_id=? AND tax_kind=? AND revoked_at IS NULL",
                        now, subjectType, subjectId, kind);
            }
            Records.audit(c, admin.id(), exempt ? "TAX_EXEMPT" : "TAX_UNEXEMPT", subjectType.toLowerCase(Locale.ROOT), subjectId, kind.name(), now);
            return null;
        });
    }

    public CompletableFuture<List<TaxRate>> currentRates(Actor admin) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            long now = clock.getAsLong();
            List<TaxRate> out = new ArrayList<>();
            for (TaxKind k : TaxKind.values()) effective(c, k, now).ifPresent(out::add);
            return out;
        });
    }

    // ------------------------------------------------------------------ lecture pour les interfaces

    public record Exemption(String subjectType, String subjectId, String subjectName, TaxKind kind, long createdAt) { }

    private static final String RATE_SELECT = "SELECT id,kind,base,rate_bp,enabled,mode,period_minutes,effective_from,created_by,created_at FROM taxes ";
    private static final Sql.RowMapper<TaxRate> RATE = rs -> new TaxRate(rs.getLong(1), TaxKind.valueOf(rs.getString(2)), TaxBase.valueOf(rs.getString(3)),
            rs.getInt(4), rs.getInt(5) == 1, TaxMode.valueOf(rs.getString(6)), rs.getInt(7), rs.getLong(8), rs.getString(9), rs.getLong(10));

    /** Derniere configuration enregistree par type de taxe (peut prendre effet a la prochaine periode). */
    public CompletableFuture<List<TaxRate>> latestRates(Actor admin) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            List<TaxRate> out = new ArrayList<>();
            for (TaxKind k : TaxKind.values()) Sql.one(c, RATE_SELECT + "WHERE kind = ? ORDER BY id DESC LIMIT 1", RATE, k).ifPresent(out::add);
            return out;
        });
    }

    /** Taux actuellement en vigueur (lisible par tous les joueurs). */
    public CompletableFuture<List<TaxRate>> publicRates() {
        return db.read(c -> {
            long now = clock.getAsLong();
            List<TaxRate> out = new ArrayList<>();
            for (TaxKind k : TaxKind.values()) effective(c, k, now).ifPresent(out::add);
            return out;
        });
    }

    public CompletableFuture<Set<TxType>> personalIncomeTypes() { return db.read(TaxManager::personalTypes); }

    public CompletableFuture<Integer> periodMinutes() { return db.read(TaxManager::periodMinutes); }

    public CompletableFuture<List<Exemption>> exemptions(Actor admin) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, "SELECT e.subject_type, e.subject_id, COALESCE(p.name, co.name, '?'), e.tax_kind, e.created_at FROM tax_exemptions e "
                    + "LEFT JOIN players p ON e.subject_type = 'PLAYER' AND p.uuid = e.subject_id "
                    + "LEFT JOIN companies co ON e.subject_type = 'COMPANY' AND CAST(co.id AS TEXT) = e.subject_id "
                    + "WHERE e.revoked_at IS NULL ORDER BY e.id DESC LIMIT 50",
                    rs -> new Exemption(rs.getString(1), rs.getString(2), rs.getString(3), TaxKind.valueOf(rs.getString(4)), rs.getLong(5)));
        });
    }

    // ------------------------------------------------------------------ cloture des periodes

    /**
     * Genere les declarations de toutes les periodes terminees depuis la derniere cloture. A appeler regulierement
     * (tick serveur / demarrage) : idempotent et rattrape un serveur reste eteint pendant plusieurs dimanches.
     * Premiere execution : on s'aligne sur la periode courante (pas d'imposition retroactive avant l'installation).
     * @return nombre de declarations creees
     */
    public CompletableFuture<Integer> closePeriods() {
        return db.write(c -> {
            long now = clock.getAsLong();
            ZoneId z = zone(c);
            int minutes = periodMinutes(c);
            long currentStart = TaxPeriods.floorBoundary(now, minutes, z);
            Optional<String> cu = Settings.get(c, Settings.TAX_CLOSED_UNTIL);
            if (cu.isEmpty()) {
                Settings.set(c, Settings.TAX_CLOSED_UNTIL, Long.toString(currentStart), "system", now);
                return 0;
            }
            long closedUntil = Long.parseLong(cu.get());
            int created = 0;
            for (int i = 0; i < MAX_CATCHUP_PERIODS; i++) {
                long next = TaxPeriods.nextBoundary(closedUntil, minutes, z);
                if (next > now) break; // periode pas encore terminee
                created += generate(c, closedUntil, next, now);
                closedUntil = next;
                Settings.set(c, Settings.TAX_CLOSED_UNTIL, Long.toString(closedUntil), "system", now);
            }
            return created;
        });
    }

    private int generate(Connection c, long start, long end, long now) throws SQLException {
        int created = 0;
        Optional<TaxRate> salaryTax = effective(c, TaxKind.SALARY, start).filter(t -> t.enabled() && t.rateBp() > 0);
        for (TaxKind kind : TaxKind.values()) {
            Optional<TaxRate> rate = effective(c, kind, start).filter(t -> t.enabled() && t.rateBp() > 0);
            if (rate.isEmpty()) continue;
            TaxRate t = rate.get();
            AccountType acct = kind == TaxKind.BUSINESS_INCOME ? AccountType.BUSINESS : AccountType.PERSONAL;
            Map<String, Long> bases = switch (kind) {
                case BUSINESS_INCOME -> t.base() == TaxBase.REVENUE ? businessRevenue(c, start, end) : businessProfit(c, start, end);
                case SALARY -> personalIncome(c, EnumSet.of(TxType.SALARY), start, end);
                case PERSONAL_INCOME -> {
                    Set<TxType> types = personalTypes(c);
                    if (salaryTax.isPresent()) types.remove(TxType.SALARY); // evite de taxer le meme salaire deux fois
                    yield personalIncome(c, types, start, end);
                }
            };
            for (Map.Entry<String, Long> e : bases.entrySet()) {
                long base = e.getValue();
                if (base <= 0) continue;
                boolean exempt = isExempt(c, acct, e.getKey(), kind);
                long amount = exempt ? 0 : TaxPeriods.percentOfBp(base, t.rateBp());
                if (amount == 0 && !exempt) continue; // arrondi a zero : rien a declarer
                long hid = insertDeclaration(c, t, kind, acct, e.getKey(), base, amount, start, end, exempt ? "WAIVED" : "DUE", now);
                if (hid < 0) continue; // deja genere (rejeu)
                created++;
                if (exempt) continue;
                if (t.mode() == TaxMode.AUTO) settleAuto(c, hid, acct, e.getKey(), amount, kind, now);
                else notifyTaxpayer(c, acct, e.getKey(), "TAX_DECLARED",
                        "Déclaration " + label(kind) + " : " + amount + " PO a régler (base " + base + " PO, " + rateText(t.rateBp()) + ").", now);
            }
        }
        return created;
    }

    private long insertDeclaration(Connection c, TaxRate t, TaxKind kind, AccountType acct, String accountId, long base, long amount,
                                   long start, long end, String status, long now) throws SQLException {
        int rows = Sql.update(c, "INSERT OR IGNORE INTO tax_history(tax_id,tax_kind,account_type,account_id,base_amount,rate_bp,amount,"
                + "period_start,period_end,status,recipient_type,created_at) VALUES (?,?,?,?,?,?,?,?,?,?, 'TREASURY', ?)",
                t.id(), kind, acct, accountId, base, t.rateBp(), amount, start, end, status, now);
        if (rows == 0) return -1;
        return Sql.scalar(c, "SELECT last_insert_rowid()");
    }

    private void settleAuto(Connection c, long hid, AccountType acct, String accountId, long amount, TaxKind kind, long now) throws SQLException {
        try {
            TxResult r = TransactionManager.apply(c, taxTx(hid, acct, accountId, amount, null));
            Sql.update(c, "UPDATE tax_history SET status='PAID', tx_id=? WHERE id=? AND status='DUE'", r.id(), hid);
            notifyTaxpayer(c, acct, accountId, "TAX_COLLECTED", "Taxe prélevée (" + label(kind) + ") : " + amount + " PO.", now);
        } catch (EconomyException e) {
            if (!e.code().equals("INSUFFICIENT_FUNDS") && !e.code().equals("COMPANY_NOT_ACTIVE")) throw e;
            notifyTaxpayer(c, acct, accountId, "TAX_DECLARED",
                    "Prélèvement impossible (fonds insuffisants) : " + amount + " PO de " + label(kind) + " restent a régler.", now);
        }
    }

    // ------------------------------------------------------------------ paiement manuel

    public CompletableFuture<TxResult> pay(Actor actor, long declarationId) {
        return db.write(c -> {
            TaxDeclaration d = declaration(c, declarationId);
            if (!d.status().equals("DUE")) throw new EconomyException("BAD_STATE", "Cette déclaration n'est pas a régler.");
            if (d.accountType() == AccountType.PERSONAL) {
                if (!d.accountId().equals(actor.id().toString())) throw new EconomyException("FORBIDDEN", "Ce n'est pas votre déclaration.");
            } else {
                PermissionManager.authorize(c, actor, Long.parseLong(d.accountId()), Permission.MANAGE_FINANCES, true);
            }
            TxResult r = TransactionManager.apply(c, taxTx(declarationId, d.accountType(), d.accountId(), d.amount(), actor.id()));
            Sql.update(c, "UPDATE tax_history SET status='PAID', tx_id=? WHERE id=? AND status='DUE'", r.id(), declarationId);
            Records.notify(c, actor.id(), "TAX_COLLECTED", "Taxe réglée : " + d.amount() + " PO (" + label(d.kind()) + ").", clock.getAsLong());
            return r;
        });
    }

    /** Exoneration ponctuelle d'une declaration par l'administration (journalisee). */
    public CompletableFuture<Void> waive(Actor admin, long declarationId, String reason) {
        return db.write(c -> {
            PermissionManager.requireAdmin(admin);
            TaxDeclaration d = declaration(c, declarationId);
            if (!d.status().equals("DUE")) throw new EconomyException("BAD_STATE", "Cette déclaration n'est pas a régler.");
            String why = Validation.text(reason, 3, 300, "Motif");
            Sql.update(c, "UPDATE tax_history SET status='WAIVED' WHERE id=? AND status='DUE'", declarationId);
            Records.audit(c, admin.id(), "TAX_WAIVED", "tax_history", declarationId, why, clock.getAsLong());
            return null;
        });
    }

    public CompletableFuture<List<TaxDeclaration>> myDeclarations(Actor actor, int limit) {
        return db.read(c -> declarations(c, AccountType.PERSONAL, actor.id().toString(), limit));
    }

    public CompletableFuture<List<TaxDeclaration>> companyDeclarations(Actor actor, long companyId, int limit) {
        return db.read(c -> {
            PermissionManager.authorize(c, actor, companyId, Permission.VIEW_FINANCES, false);
            return declarations(c, AccountType.BUSINESS, Long.toString(companyId), limit);
        });
    }

    /** Historique fiscal complet pour l'administration (filtre optionnel sur le statut). */
    public CompletableFuture<List<TaxDeclaration>> allDeclarations(Actor admin, String statusOrNull, int limit, int offset) {
        return db.read(c -> {
            PermissionManager.requireAdmin(admin);
            return Sql.query(c, DECL_SELECT + "WHERE (? IS NULL OR status = ?) ORDER BY id DESC LIMIT ? OFFSET ?", DECL,
                    statusOrNull, statusOrNull, Math.max(1, Math.min(limit, 200)), Math.max(0, offset));
        });
    }

    // ------------------------------------------------------------------ bases imposables

    private static Map<String, Long> businessRevenue(Connection c, long s, long e) throws SQLException {
        return map(c, "SELECT target_id, SUM(amount) FROM transactions WHERE type='INVOICE_PAYMENT' AND target_type='BUSINESS' "
                + "AND created_at >= ? AND created_at < ? AND CAST(target_id AS INTEGER) IN (SELECT id FROM companies WHERE status <> 'CLOSED') GROUP BY target_id", s, e);
    }

    /** Benefice = chiffre d'affaires - (salaires verses + factures payees), plancher a 0. */
    private static Map<String, Long> businessProfit(Connection c, long s, long e) throws SQLException {
        Map<String, Long> revenue = businessRevenue(c, s, e);
        Map<String, Long> expenses = map(c, "SELECT source_id, SUM(amount) FROM transactions WHERE source_type='BUSINESS' "
                + "AND type IN ('SALARY','INVOICE_PAYMENT') AND created_at >= ? AND created_at < ? GROUP BY source_id", s, e);
        Map<String, Long> out = new HashMap<>();
        revenue.forEach((k, v) -> out.put(k, Math.max(0, v - expenses.getOrDefault(k, 0L))));
        return out;
    }

    private static Map<String, Long> personalIncome(Connection c, Set<TxType> types, long s, long e) throws SQLException {
        if (types.isEmpty()) return Map.of();
        String in = types.stream().map(t -> "?").collect(Collectors.joining(","));
        List<Object> params = new ArrayList<>(types);
        params.add(s);
        params.add(e);
        return map(c, "SELECT target_id, SUM(amount) FROM transactions WHERE target_type='PERSONAL' AND type IN (" + in + ") "
                + "AND created_at >= ? AND created_at < ? GROUP BY target_id", params.toArray());
    }

    private static Map<String, Long> map(Connection c, String sql, Object... params) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        Sql.query(c, sql, rs -> { out.put(rs.getString(1), rs.getLong(2)); return 1; }, params);
        return out;
    }

    private static Set<TxType> personalTypes(Connection c) throws SQLException {
        Set<TxType> out = EnumSet.noneOf(TxType.class);
        String csv = Settings.get(c, Settings.TAX_PERSONAL_TYPES).orElse("SALARY");
        for (String s : csv.split(",")) {
            if (s.isBlank()) continue;
            TxType t = TxType.valueOf(s.trim());
            if (SELECTABLE_PERSONAL_TYPES.contains(t)) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ interne

    private static TxRequest taxTx(long declarationId, AccountType acct, String accountId, long amount, UUID initiator) {
        UUID id = UUID.nameUUIDFromBytes(("citizeneconomy:tax:" + declarationId).getBytes(StandardCharsets.UTF_8));
        AccountRef src = acct == AccountType.BUSINESS ? new AccountRef(AccountType.BUSINESS, accountId) : new AccountRef(AccountType.PERSONAL, accountId);
        return new TxRequest(id, TxType.TAX, amount, src, AccountRef.treasury(), "Taxe #" + declarationId, initiator);
    }

    private static void notifyTaxpayer(Connection c, AccountType acct, String accountId, String type, String msg, long now) throws SQLException {
        if (acct == AccountType.PERSONAL) {
            Records.notify(c, UUID.fromString(accountId), type, msg, now);
        } else {
            Company co = Records.company(c, Long.parseLong(accountId));
            Records.notify(c, co.owner(), type, "« " + co.name() + " » : " + msg, now);
            if (co.manager() != null) Records.notify(c, co.manager(), type, "« " + co.name() + " » : " + msg, now);
        }
    }

    private static boolean isExempt(Connection c, AccountType acct, String id, TaxKind kind) throws SQLException {
        return Sql.exists(c, "SELECT 1 FROM tax_exemptions WHERE subject_type=? AND subject_id=? AND tax_kind=? AND revoked_at IS NULL",
                acct == AccountType.BUSINESS ? "COMPANY" : "PLAYER", id, kind);
    }

    private static Optional<TaxRate> effective(Connection c, TaxKind kind, long at) throws SQLException {
        return Sql.one(c, "SELECT id,kind,base,rate_bp,enabled,mode,period_minutes,effective_from,created_by,created_at FROM taxes "
                + "WHERE kind = ? AND effective_from <= ? ORDER BY effective_from DESC, id DESC LIMIT 1",
                rs -> new TaxRate(rs.getLong(1), TaxKind.valueOf(rs.getString(2)), TaxBase.valueOf(rs.getString(3)), rs.getInt(4),
                        rs.getInt(5) == 1, TaxMode.valueOf(rs.getString(6)), rs.getInt(7), rs.getLong(8), rs.getString(9), rs.getLong(10)),
                kind, at);
    }

    private static final String DECL_SELECT = "SELECT id,tax_kind,account_type,account_id,base_amount,rate_bp,amount,period_start,period_end,status,tx_id,created_at FROM tax_history ";
    private static final Sql.RowMapper<TaxDeclaration> DECL = rs -> new TaxDeclaration(rs.getLong(1), TaxKind.valueOf(rs.getString(2)),
            AccountType.valueOf(rs.getString(3)), rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
            rs.getString(10), rs.getObject(11) == null ? null : rs.getLong(11), rs.getLong(12));

    private static TaxDeclaration declaration(Connection c, long id) throws SQLException {
        return Sql.one(c, DECL_SELECT + "WHERE id = ?", DECL, id).orElseThrow(() -> new EconomyException("NOT_FOUND", "Déclaration introuvable."));
    }

    private static List<TaxDeclaration> declarations(Connection c, AccountType acct, String accountId, int limit) throws SQLException {
        return Sql.query(c, DECL_SELECT + "WHERE account_type = ? AND account_id = ? ORDER BY id DESC LIMIT ?", DECL, acct, accountId, Math.max(1, Math.min(limit, 100)));
    }

    private static ZoneId zone(Connection c) throws SQLException {
        return ZoneId.of(Settings.get(c, Settings.TAX_ZONE).orElse(ZoneId.systemDefault().getId()));
    }

    private static int periodMinutes(Connection c) throws SQLException {
        return Settings.get(c, Settings.TAX_PERIOD_MINUTES).map(Integer::parseInt).orElse(TaxPeriods.WEEKLY);
    }

    private static String label(TaxKind k) {
        return switch (k) { case BUSINESS_INCOME -> "taxe sur les entreprises"; case PERSONAL_INCOME -> "taxe sur les revenus"; case SALARY -> "taxe sur les salaires"; };
    }

    private static String rateText(int bp) { return String.format(Locale.ROOT, "%.2f %%", bp / 100.0); }
}
