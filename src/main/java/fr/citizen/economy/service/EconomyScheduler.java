package fr.citizen.economy.service;

import fr.citizen.economy.persistence.Database;

import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Taches periodiques, appelees chaque minute par le serveur. Toutes idempotentes et rattrapables apres arret :
 * cloture fiscale, paie, factures en retard, sauvegarde, purge. Une erreur dans une tache n'empeche pas les autres.
 */
public final class EconomyScheduler {
    public static final String PAYROLL_PERIOD = "payroll.period_minutes";
    public static final String PAYROLL_LAST = "payroll.last_boundary";
    public static final String BACKUP_INTERVAL = "backup.interval_minutes";
    public static final String BACKUP_LAST = "backup.last";
    public static final int DEFAULT_BACKUP_MINUTES = 360;
    public static final int BACKUP_KEEP = 30;

    private final Database db;
    private final TaxManager tax;
    private final PayrollManager payroll;
    private final InvoiceManager invoices;
    private final NotificationManager notifications;
    private final LongSupplier clock;
    private final Consumer<String> log;

    public EconomyScheduler(Database db, TaxManager tax, PayrollManager payroll, InvoiceManager invoices,
                            NotificationManager notifications, LongSupplier clock, Consumer<String> log) {
        this.db = db; this.tax = tax; this.payroll = payroll; this.invoices = invoices;
        this.notifications = notifications; this.clock = clock; this.log = log;
    }

    /** Execute toutes les taches ; bloquant (a appeler depuis un thread de travail, jamais le thread serveur). */
    public void tick() {
        run("clôture fiscale", () -> tax.closePeriods().join());
        run("paie", this::payrollStep);
        run("factures en retard", () -> invoices.markOverdue().join());
        run("sauvegarde", this::backupStep);
    }

    /** Purge quotidienne : a appeler rarement. */
    public void maintenance() {
        run("purge notifications", () -> notifications.purgeOld(90).join());
    }

    private void run(String name, Runnable r) {
        try { r.run(); } catch (Throwable t) { log.accept("Tâche '" + name + "' en échec : " + EconomyException.unwrap(t)); }
    }

    /** Paie la periode courante une seule fois (identifiants deterministes : sans risque de double paiement). */
    void payrollStep() {
        long now = clock.getAsLong();
        long[] decision = db.read(c -> {
            ZoneId z = ZoneId.of(Settings.get(c, Settings.TAX_ZONE).orElse(ZoneId.systemDefault().getId()));
            int minutes = Settings.get(c, PAYROLL_PERIOD).map(Integer::parseInt).orElse(TaxPeriods.WEEKLY);
            long boundary = TaxPeriods.floorBoundary(now, minutes, z);
            long last = Settings.get(c, PAYROLL_LAST).map(Long::parseLong).orElse(-1L);
            return new long[]{boundary, last};
        }).join();
        long boundary = decision[0], last = decision[1];
        if (last == boundary) return;
        if (last >= 0) payroll.runAll(boundary).join(); // premiere execution : on s'aligne sans paie retroactive
        db.write(c -> { Settings.set(c, PAYROLL_LAST, Long.toString(boundary), "system", now); return null; }).join();
    }

    void backupStep() {
        long now = clock.getAsLong();
        boolean due = db.read(c -> {
            int minutes = Settings.get(c, BACKUP_INTERVAL).map(Integer::parseInt).orElse(DEFAULT_BACKUP_MINUTES);
            long last = Settings.get(c, BACKUP_LAST).map(Long::parseLong).orElse(0L);
            return now - last >= minutes * 60_000L;
        }).join();
        if (!due) return;
        db.backup("auto", BACKUP_KEEP).join();
        db.write(c -> { Settings.set(c, BACKUP_LAST, Long.toString(now), "system", now); return null; }).join();
    }
}
