package fr.citizen.economy.domain;

import java.util.UUID;

public record AccountRef(AccountType type, String id) {
    public static AccountRef personal(UUID player) { return new AccountRef(AccountType.PERSONAL, player.toString()); }
    public static AccountRef business(long companyId) { return new AccountRef(AccountType.BUSINESS, Long.toString(companyId)); }
    public static AccountRef treasury() { return new AccountRef(AccountType.TREASURY, "1"); }
    public static AccountRef cash() { return new AccountRef(AccountType.CASH, null); }
}
