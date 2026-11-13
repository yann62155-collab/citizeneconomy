package fr.citizen.economy.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Migrations numerotees et ordonnees. Regles : une migration deja livree n'est JAMAIS modifiee,
 * on en ajoute une nouvelle. Une base plus recente que le mod est refusee (pas de downgrade silencieux).
 */
final class Migrator {

    /** Version N = element N-1. A completer a chaque evolution du schema. */
    static final List<String> FILES = List.of("V1__init.sql", "V2__notification_delivery.sql", "V3__company_matricule.sql");

    private Migrator() { }

    static void migrate(Connection c, Path backupDir) throws SQLException, IOException {
        exec(c, "CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL)");
        int current = currentVersion(c);
        if (current > FILES.size()) {
            throw new IllegalStateException("La base est en version " + current
                    + " mais ce mod ne connaît que la version " + FILES.size()
                    + ". Refus de démarrer pour protéger les donnees.");
        }
        if (current > 0 && current < FILES.size()) {
            backupTo(c, backupDir, "pre-migration-v" + current);
        }
        for (int v = current + 1; v <= FILES.size(); v++) {
            String name = FILES.get(v - 1);
            List<String> statements = load("/db/migration/" + name);
            exec(c, "BEGIN IMMEDIATE");
            try {
                for (String sql : statements) exec(c, sql);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO schema_version(version, name, applied_at) VALUES (?,?,?)")) {
                    ps.setInt(1, v);
                    ps.setString(2, name);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                exec(c, "COMMIT");
            } catch (SQLException | RuntimeException e) {
                try { exec(c, "ROLLBACK"); } catch (SQLException ignored) { }
                throw e;
            }
        }
    }

    static int currentVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Sauvegarde coherente via VACUUM INTO (doit etre appelee hors transaction). */
    static Path backupTo(Connection c, Path dir, String label) throws SQLException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path target = dir.resolve(stamp + "-" + label.replaceAll("[^A-Za-z0-9_.-]", "_") + ".db");
        String escaped = target.toAbsolutePath().toString().replace("'", "''");
        exec(c, "VACUUM INTO '" + escaped + "'");
        return target;
    }

    private static List<String> load(String resource) throws IOException {
        try (InputStream in = Migrator.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Migration introuvable : " + resource);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder cleaned = new StringBuilder();
            for (String line : text.split("\n")) {
                if (!line.trim().startsWith("--")) cleaned.append(line).append('\n');
            }
            List<String> out = new ArrayList<>();
            for (String stmt : cleaned.toString().split(";")) {
                String s = stmt.trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
}
