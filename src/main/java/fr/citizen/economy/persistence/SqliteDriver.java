package fr.citizen.economy.persistence;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;

/**
 * Fournit le driver SQLite. En production, le driver est embarque comme simple ressource du JAR (lib/sqlite-jdbc.jar),
 * extrait dans le dossier du mod et charge dans un chargeur de classes ISOLE : ainsi il ne cohabite jamais avec le
 * package org.sqlite d'un autre mod (cause d'un plantage de NeoForge « export package org.sqlite » en double).
 * Sans cette ressource (tests, environnement de developpement), on utilise le driver present sur le classpath.
 */
final class SqliteDriver {
    private static final String RESOURCE = "/lib/sqlite-jdbc.jar";
    private static Driver cached;

    private SqliteDriver() { }

    static synchronized Driver get(Path libDir) throws Exception {
        if (cached != null) return cached;
        if ("classpath".equals(System.getProperty("citizeneconomy.driver"))) { // tests : driver du classpath (rien d'extrait ni de verrouille)
            cached = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
            return cached;
        }
        try (InputStream in = SqliteDriver.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Class<?> cls = Class.forName("org.sqlite.JDBC", true, SqliteDriver.class.getClassLoader());
                cached = (Driver) cls.getDeclaredConstructor().newInstance();
                return cached;
            }
            Files.createDirectories(libDir);
            Path jar = libDir.resolve("sqlite-jdbc.jar");
            Path tmp = libDir.resolve("sqlite-jdbc.jar.tmp");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!Files.exists(jar) || Files.size(jar) != Files.size(tmp)) Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            else Files.deleteIfExists(tmp);
            ClassLoader loader = new ChildFirst(jar.toUri().toURL(), SqliteDriver.class.getClassLoader());
            Class<?> cls = Class.forName("org.sqlite.JDBC", true, loader);
            cached = (Driver) cls.getDeclaredConstructor().newInstance();
            return cached;
        }
    }

    /** Charge org.sqlite.* depuis le JAR embarque avant de demander au parent (le parent fournit slf4j et le JDK). */
    private static final class ChildFirst extends URLClassLoader {
        ChildFirst(URL url, ClassLoader parent) { super(new URL[]{url}, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.sqlite.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
