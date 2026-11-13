package fr.citizen.economy.domain;
public record Invoice(long id, String number, long issuerCompanyId, String issuerName, String recipientType,
                      String recipientId, String recipientName, String description, long amount, long issuedAt,
                      Long dueAt, String status, Long paidTxId) { }
