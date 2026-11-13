package fr.citizen.economy.web;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Codes de connexion a usage unique (8 chiffres, 5 minutes), obtenus en jeu avec /ceconomy web. */
public final class LoginCodes {
    public record Owner(UUID id, String name) { }
    private record Entry(Owner owner, long expiresAt) { }

    static final long TTL_MS = 5 * 60_000L;
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();
    private final LongSupplier clock;

    public LoginCodes(LongSupplier clock) { this.clock = clock; }

    public String issue(UUID id, String name) {
        purge();
        codes.values().removeIf(e -> e.owner().id().equals(id)); // un seul code actif par joueur
        String code;
        do { code = String.format("%08d", rnd.nextInt(100_000_000)); } while (codes.containsKey(code));
        codes.put(code, new Entry(new Owner(id, name), clock.getAsLong() + TTL_MS));
        return code;
    }

    /** Consomme le code (usage unique). */
    public Optional<Owner> consume(String code) {
        if (code == null) return Optional.empty();
        Entry e = codes.remove(code.trim());
        if (e == null || e.expiresAt() < clock.getAsLong()) return Optional.empty();
        return Optional.of(e.owner());
    }

    private void purge() {
        long now = clock.getAsLong();
        for (Iterator<Entry> it = codes.values().iterator(); it.hasNext(); ) if (it.next().expiresAt() < now) it.remove();
    }
}
