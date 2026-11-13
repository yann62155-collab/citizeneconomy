package fr.citizen.economy.domain;

public record LedgerLine(long txId, TxType type, long delta, long balanceAfter, String description, long createdAt) { }
