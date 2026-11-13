package fr.citizen.economy.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Petits utilitaires JDBC : requetes toujours parametrees (aucune concatenation de donnees utilisateur). */
final class Sql {
    @FunctionalInterface interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private Sql() { }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object o = params[i];
            if (o == null) ps.setNull(i + 1, Types.NULL);
            else if (o instanceof UUID u) ps.setString(i + 1, u.toString());
            else if (o instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else if (o instanceof Enum<?> e) ps.setString(i + 1, e.name());
            else ps.setObject(i + 1, o);
        }
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, params); return ps.executeUpdate(); }
    }

    static long insert(Connection c, String sql, Object... params) throws SQLException {
        update(c, sql, params);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        }
    }

    static <T> Optional<T> one(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> l = query(c, sql, mapper, params);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    static boolean exists(Connection c, String sql, Object... params) throws SQLException {
        return !query(c, sql, rs -> 1, params).isEmpty();
    }

    static long scalar(Connection c, String sql, Object... params) throws SQLException {
        return query(c, sql, rs -> rs.getLong(1), params).stream().findFirst().orElse(0L);
    }

    static boolean isUnique(SQLException e, String column) {
        String m = e.getMessage();
        return m != null && m.contains("UNIQUE constraint failed") && m.contains(column);
    }
}
