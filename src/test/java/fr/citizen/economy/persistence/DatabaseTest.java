package fr.citizen.economy.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    @TempDir Path tmp;

    private Database newDb() {
        return new Database(tmp.resolve("t.db"), tmp.resolve("backups"));
    }

    @Test
    void migrationCreatesAllTablesAndIsIdempotent() throws Exception {
        Set<String> expected = Set.of("companies", "company_requests", "employees", "players", "personal_accounts",
                "business_accounts", "transactions", "ledger_entries", "taxes", "tax_history", "treasury",
                "announcements", "invoices", "notifications", "audit_logs", "schema_version");
        for (int i = 0; i < 2; i++) { // 2e ouverture = pas de re-migration
            try (Database db = newDb()) {
                db.open();
                Set<String> tables = db.read(c -> {
                    Set<String> out = new HashSet<>();
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                        while (rs.next()) out.add(rs.getString(1));
                    }
                    return out;
                }).join();
                assertTrue(tables.containsAll(expected), "tables manquantes : " + expected);
                int v = db.read(c -> Migrator.currentVersion(c)).join();
                assertEquals(Migrator.FILES.size(), v);
            }
        }
    }

    @Test
    void duplicateTransactionUuidIsRejected() throws Exception {
        try (Database db = newDb()) {
            db.open();
            Runnable insert = () -> db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO transactions(tx_uuid,type,amount,target_type,target_id,created_at) VALUES ('abc','DEPOSIT',10,'PERSONAL','x',1)")) {
                    return ps.executeUpdate();
                }
            }).join();
            insert.run();
            CompletionException e = assertThrows(CompletionException.class, insert::run);
            assertInstanceOf(SQLException.class, e.getCause());
        }
    }

    @Test
    void negativeBalanceIsRejectedAndRolledBack() throws Exception {
        try (Database db = newDb()) {
            db.open();
            assertThrows(CompletionException.class, () -> db.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("UPDATE treasury SET balance = balance + 5 WHERE id=1");
                    s.executeUpdate("UPDATE treasury SET balance = balance - 100 WHERE id=1");
                }
                return null;
            }).join());
            long bal = db.read(c -> {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT balance FROM treasury")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }).join();
            assertEquals(0L, bal, "le +5 doit avoir ete annule (rollback)");
        }
    }

    @Test
    void concurrentWritesAreSerialized() throws Exception {
        try (Database db = newDb()) {
            db.open();
            // pool dedie : le pool commun de la JVM peut etre trop petit sur une machine a peu de coeurs
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
            try {
                List<CompletableFuture<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    futures.add(CompletableFuture.supplyAsync(() -> db.write(c -> {
                        try (Statement s = c.createStatement()) {
                            return s.executeUpdate("UPDATE treasury SET balance = balance + 1 WHERE id=1");
                        }
                    }).join(), pool));
                }
                futures.forEach(CompletableFuture::join);
            } finally {
                pool.shutdown();
            }
            long bal = db.read(c -> {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT balance FROM treasury")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }).join();
            assertEquals(300L, bal);
        }
    }

    @Test
    void backupProducesReadableCopy() throws Exception {
        try (Database db = newDb()) {
            db.open();
            Path b = db.backup("test", 5).join();
            assertTrue(java.nio.file.Files.size(b) > 0);
        }
    }

    @Test
    void migrationGivesAMatriculeToExistingCompanies() throws Exception {
        String owner = java.util.UUID.randomUUID().toString();
        try (Database db = newDb()) {
            db.open();
            db.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO players(uuid,name,first_seen,last_seen) VALUES ('" + owner + "','Old',1,1)");
                    for (String n : List.of("Ancienne A", "Ancienne B")) {
                        s.executeUpdate("INSERT INTO companies(uid,name,emblem,description,sector,created_at,updated_at,owner_uuid,status) VALUES ('"
                                + n + "','" + n + "','','desc','Forge',1,1,'" + owner + "','ACTIVE')");
                    }
                    // retour a l'etat "avant V3"
                    s.executeUpdate("DROP INDEX ux_companies_matricule");
                    s.executeUpdate("ALTER TABLE companies DROP COLUMN matricule");
                    s.executeUpdate("DELETE FROM schema_version WHERE version = 3");
                }
                return null;
            }).join();
        }
        try (Database db = newDb()) {
            db.open();
            List<String> m = db.read(c -> {
                List<String> out = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT matricule FROM companies ORDER BY id")) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                return out;
            }).join();
            assertEquals(List.of("E-0001", "E-0002"), m);
        }
    }
}
