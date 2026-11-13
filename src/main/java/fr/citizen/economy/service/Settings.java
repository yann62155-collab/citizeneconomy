package fr.citizen.economy.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** Parametres persistants modifiables a chaud (table settings). */
final class Settings {
    static final String TAX_PERIOD_MINUTES = "tax.period_minutes";
    static final String TAX_ZONE = "tax.zone";
    static final String TAX_CLOSED_UNTIL = "tax.closed_until";
    static final String TAX_PERSONAL_TYPES = "tax.personal.income_types";

    private Settings() { }

    static Optional<String> get(Connection c, String key) throws SQLException {
        return Sql.one(c, "SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), key);
    }

    static void set(Connection c, String key, String value, String by, long now) throws SQLException {
        Sql.update(c, "INSERT INTO settings(key,value,updated_at,updated_by) VALUES (?,?,?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at, updated_by=excluded.updated_by",
                key, value, now, by);
    }
}
