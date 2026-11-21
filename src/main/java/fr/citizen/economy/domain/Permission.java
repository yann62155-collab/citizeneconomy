package fr.citizen.economy.domain;

/** Permissions d'entreprise, stockees sous forme de masque de bits (employees.permissions). */
public enum Permission {
    MANAGE_EMPLOYEES(true),
    MANAGE_SALARIES(true),
    MANAGE_PERMISSIONS(true),
    MANAGE_FINANCES(true),
    MANAGE_ANNOUNCEMENTS(true),
    PUBLISH_ANNOUNCEMENTS(false),
    VIEW_FINANCES(false),
    ISSUE_INVOICES(false),
    /** Caisse : encaisser des ventes. */
    CASHIER(false),
    /** Stock : creer des produits, reapprovisionner, corriger. */
    MANAGE_STOCK(false),
    /** Comptabilite : journal, compte de resultat, bilan, ecritures manuelles. */
    VIEW_ACCOUNTING(false);

    /** Reservee au gerant/proprietaire : ne peut jamais etre accordee a un simple employe. */
    public final boolean managerOnly;
    Permission(boolean managerOnly) { this.managerOnly = managerOnly; }
    public int bit() { return 1 << ordinal(); }
}
