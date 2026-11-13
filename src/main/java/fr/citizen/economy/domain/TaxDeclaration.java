package fr.citizen.economy.domain;
public record TaxDeclaration(long id, TaxKind kind, AccountType accountType, String accountId, long baseAmount,
                             int rateBp, long amount, long periodStart, long periodEnd, String status, Long txId,
                             long createdAt) { }
