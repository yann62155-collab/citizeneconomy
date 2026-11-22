package fr.citizen.economy.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Index en memoire des coffres d'entreprise et des membres : repond tout de suite (thread serveur), sans toucher a la base.
 * Les coffres sont modifies uniquement par ce serveur, donc l'index local fait foi ; les membres sont rafraichis regulierement
 * (un licenciement peut donc mettre quelques secondes a prendre effet).
 */
public final class ChestLocks {
    private final Map<String, Long> chests = new ConcurrentHashMap<>();
    private volatile ChestManager.Snapshot snap = new ChestManager.Snapshot(Map.of(), Map.of(), Map.of());

    public static String key(String dim, int x, int y, int z) { return dim + "|" + x + "|" + y + "|" + z; }

    public void loadChests(Collection<ChestManager.Chest> all) {
        chests.clear();
        for (ChestManager.Chest ch : all) chests.put(key(ch.dimension(), ch.x(), ch.y(), ch.z()), ch.companyId());
    }

    public void loadMembers(ChestManager.Snapshot s) { this.snap = s; }

    public void add(String dim, int x, int y, int z, long companyId) { chests.put(key(dim, x, y, z), companyId); }
    public void remove(String dim, int x, int y, int z) { chests.remove(key(dim, x, y, z)); }

    /** Entreprise proprietaire du coffre a cette position, ou null si ce n'est pas un coffre d'entreprise. */
    public Long companyAt(String dim, int x, int y, int z) { return chests.get(key(dim, x, y, z)); }

    public boolean isMember(UUID player, long companyId) { return snap.members().getOrDefault(player, Set.of()).contains(companyId); }
    public boolean isStaff(UUID player, long companyId) { return snap.staff().getOrDefault(player, Set.of()).contains(companyId); }
    public String name(long companyId) { return snap.names().getOrDefault(companyId, "une entreprise"); }
    public int size() { return chests.size(); }
}
