package fr.citizen.economy.service;

import fr.citizen.economy.persistence.Database;

import java.util.function.Consumer;

/** Conteneur de tous les services metier, construit une seule fois autour d'une base ouverte. */
public final class Economy {
    public final Database db;
    public final PlayerManager players;
    public final CompanyManager companies;
    public final EmployeeManager employees;
    public final BankManager bank;
    public final TransactionManager transactions;
    public final PayrollManager payroll;
    public final TaxManager tax;
    public final TreasuryManager treasury;
    public final AnnouncementManager announcements;
    public final InvoiceManager invoices;
    public final NotificationManager notifications;
    public final AdminManager admin;
    public final EconomyScheduler scheduler;

    public Economy(Database db, Consumer<String> log) {
        this.db = db;
        players = new PlayerManager(db);
        companies = new CompanyManager(db);
        employees = new EmployeeManager(db);
        bank = new BankManager(db);
        transactions = new TransactionManager(db);
        payroll = new PayrollManager(db);
        tax = new TaxManager(db);
        treasury = new TreasuryManager(db);
        announcements = new AnnouncementManager(db);
        invoices = new InvoiceManager(db);
        notifications = new NotificationManager(db);
        admin = new AdminManager(db);
        scheduler = new EconomyScheduler(db, tax, payroll, invoices, notifications, System::currentTimeMillis, log);
    }
}
