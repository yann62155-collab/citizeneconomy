package fr.citizen.economy.domain;
/** Une ligne de taux versionnee. rateBp en points de base (1000 = 10,00 %). */
public record TaxRate(long id, TaxKind kind, TaxBase base, int rateBp, boolean enabled, TaxMode mode,
                      int periodMinutes, long effectiveFrom, String createdBy, long createdAt) { }
