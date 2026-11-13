package fr.citizen.economy.domain;

public enum TxType {
    DEPOSIT, WITHDRAWAL, TRANSFER, SALARY, INVOICE_PAYMENT, TAX, ROYAL_EXPENSE, ROYAL_REVENUE, ADJUSTMENT;

    /**
     * Seuls les salaires et les paiements de factures sont des revenus imposables.
     * Depots, retraits et simples transferts ne le sont jamais : pas de double imposition.
     */
    public boolean taxable() { return this == SALARY || this == INVOICE_PAYMENT; }
}
