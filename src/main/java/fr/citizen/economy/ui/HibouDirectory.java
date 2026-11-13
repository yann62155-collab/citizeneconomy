package fr.citizen.economy.ui;

import java.util.Optional;
import java.util.UUID;

/**
 * Annuaire des numéros de hibou (fournis par le mod Hibou Postal s'il est installé).
 * Les méthodes sont bloquantes : appelées depuis le thread de travail, jamais depuis le thread serveur.
 */
public interface HibouDirectory {
    /** Vrai si le mod Hibou Postal est présent et exploitable. */
    boolean available();

    Optional<UUID> findByNumber(int number);

    /** Numéro du joueur, ou 0 s'il n'en a pas. */
    int numberOf(UUID player);

    static String format(int number) { return String.format("%04d", number); }

    HibouDirectory NONE = new HibouDirectory() {
        public boolean available() { return false; }
        public Optional<UUID> findByNumber(int number) { return Optional.empty(); }
        public int numberOf(UUID player) { return 0; }
    };
}
