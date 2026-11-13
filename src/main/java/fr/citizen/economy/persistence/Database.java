package fr.citizen.economy.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Acces SQLite : un thread ecrivain unique (toutes les ecritures sont serialisees,
 * chacune dans une transaction BEGIN IMMEDIATE) et un thread lecteur separe (WAL).
 * Aucune requete ne doit etre executee sur le thread principal du serveur.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final Path file;
    private final Path backupDir;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> daemon(r, "CitizenEconomy-DB-Writer"));
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> daemon(r, "CitizenEconomy-DB-Reader"));
    private Connection writeConn;
    private Connection readConn;
    private volatile boolean open;

    public Database(Path file, Path backupDir) {
        this.file = file;
        this.backupDir = backupDir;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Ouvre la base, applique les migrations. Leve une exception si impossible (le serveur ne doit pas demarrer). */
    public void open() throws Exception {
        Files.createDirectories(file.getParent());
        Files.createDirectories(backupDir);
        try {
            writer.submit(() -> {
                writeConn = connect(false);
                Migrator.migrate(writeConn, backupDir);
                return null;
            }).get();
            reader.submit(() -> {
                readConn = connect(true);
                return null;
            }).get();
        } catch (java.util.concurrent.ExecutionException e) {
            close();
            Throwable c = e.getCause();
            throw c instanceof Exception ex ? ex : e;
        }
        open = true;
    }

    private Connection connect(boolean readOnly) throws Exception {
        Driver driver = SqliteDriver.get(file.toAbsolutePath().getParent().resolve("lib"));
        Connection c = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        if (c == null) throw new SQLException("Driver SQLite a refusé l'URL de connexion");
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL"); // durabilite maximale : il s'agit d'argent
            if (readOnly) s.execute("PRAGMA query_only=ON");
        }
        return c;
    }

    /** Ecriture atomique (transaction). Rollback automatique si la fonction leve une exception. */
    public <T> CompletableFuture<T> write(SqlFunction<T> fn) {
        return CompletableFuture.supplyAsync(() -> {
            requireOpen();
            try {
                exec(writeConn, "BEGIN IMMEDIATE");
                try {
                    T result = fn.apply(writeConn);
                    exec(writeConn, "COMMIT");
                    return result;
                } catch (Throwable t) {
                    try { exec(writeConn, "ROLLBACK"); } catch (SQLException ignored) { }
                    throw t;
                }
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        }, writer);
    }

    /** Lecture (sans transaction d'ecriture). */
    public <T> CompletableFuture<T> read(SqlFunction<T> fn) {
        return CompletableFuture.supplyAsync(() -> {
            requireOpen();
            try {
                return fn.apply(readConn);
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
        }, reader);
    }

    /** Sauvegarde coherente a chaud (VACUUM INTO). Conserve les {@code keep} plus recentes. */
    public CompletableFuture<Path> backup(String label, int keep) {
        return CompletableFuture.supplyAsync(() -> {
            requireOpen();
            try {
                Path target = Migrator.backupTo(writeConn, backupDir, label);
                prune(backupDir, keep);
                return target;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, writer);
    }

    private static void prune(Path dir, int keep) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            Path[] sorted = files.filter(p -> p.getFileName().toString().endsWith(".db"))
                    .sorted().toArray(Path[]::new);
            for (int i = 0; i < sorted.length - keep; i++) Files.deleteIfExists(sorted[i]);
        }
    }

    private void requireOpen() {
        if (!open) throw new IllegalStateException("Base fermée");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    @Override
    public void close() {
        open = false;
        writer.shutdown();
        reader.shutdown();
        try { writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { reader.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        closeQuietly(readConn);
        closeQuietly(writeConn);
    }

    private static void closeQuietly(Connection c) {
        if (c != null) try { c.close(); } catch (SQLException ignored) { }
    }
}
