package fr.citizen.economy.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API publique du recensement, lue par les autres mods Citizen (ex. Citizen Tab) sans toucher a la base :
 * une copie en memoire, rafraichie par Citizen Economy toutes les quelques secondes.
 * Utilisable depuis le thread serveur (aucun acces disque).
 */
public final class Recensement {
    private static final Map<UUID, String[]> CACHE = new ConcurrentHashMap<>();

    private Recensement() { }

    /**
     * Fiche du joueur : { prenom, nom, age, profession, "1" si validee sinon "0" }, ou null s'il n'est pas recense.
     * Tableau neuf a chaque appel (modifiable sans risque).
     */
    public static String[] fiche(UUID player) {
        String[] f = player == null ? null : CACHE.get(player);
        return f == null ? null : f.clone();
    }

    /** Remplace tout le cache ; renvoie les joueurs dont la fiche a change (pour rafraichir leur ligne du Tab). */
    public static java.util.Set<UUID> replaceAll(Map<UUID, String[]> fresh) {
        java.util.Set<UUID> changed = new java.util.HashSet<>();
        for (UUID id : CACHE.keySet()) if (!fresh.containsKey(id)) changed.add(id);
        for (Map.Entry<UUID, String[]> e : fresh.entrySet()) {
            String[] old = CACHE.get(e.getKey());
            if (old == null || !java.util.Arrays.equals(old, e.getValue())) changed.add(e.getKey());
        }
        CACHE.keySet().retainAll(fresh.keySet());
        fresh.forEach((k, v) -> CACHE.put(k, Objects.requireNonNull(v).clone()));
        return changed;
    }

    public static void clear() { CACHE.clear(); }
}
