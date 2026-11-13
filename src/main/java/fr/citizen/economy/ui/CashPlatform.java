package fr.citizen.economy.ui;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pont vers le monde Minecraft pour les pieces physiques. Implemente cote NeoForge ; une version en memoire
 * sert aux tests. Toutes les methodes d'inventaire s'executent sur le thread serveur via {@link #onServer}.
 */
public interface CashPlatform {
    /** Execute la tache sur le thread serveur et attend son resultat (appel bloquant depuis le thread de travail). */
    <T> T onServer(Supplier<T> task);

    /** Pieces possedees, index = ordinal de Coins.Denomination. */
    long[] countCoins(UUID player);

    void removeCoins(UUID player, long[] counts);

    /** Donne les pieces (le surplus tombe au sol). @return false si le joueur n'est pas connecte. */
    boolean giveCoins(UUID player, long[] counts);
}
