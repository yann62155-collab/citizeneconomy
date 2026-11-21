package fr.citizen.economy.platform;

import fr.citizen.economy.service.Economy;
import fr.citizen.economy.service.PlayerManager;
import fr.citizen.economy.web.DiscordDirectory;

import java.util.*;

/**
 * Relie les comptes Discord aux joueurs (via les pseudos de /pseudo) et envoie leurs metiers au bot.
 * Le pseudo Discord n'est JAMAIS devine : seul le pseudo donne par le joueur avec /pseudo compte.
 */
public final class DiscordLinks implements DiscordDirectory {
    private final Economy eco;
    private final DiscordApiFacade api;

    public DiscordLinks(Economy eco, DiscordApiFacade api) { this.eco = eco; this.api = api; }

    public boolean available() { return api.available(); }

    /** pseudo normalise -> joueur (le plus recemment vu l'emporte en cas de doublon) */
    private Map<String, UUID> index() {
        Map<String, UUID> idx = new HashMap<>();
        for (PlayerManager.PlayerRef p : eco.players.allPlayers().join()) idx.putIfAbsent(api.normalize(p.name()), p.uuid());
        return idx;
    }

    private Set<UUID> playersOf(String discordId, Map<String, UUID> idx) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String pseudo : api.allLinks().getOrDefault(discordId, List.of())) {
            UUID id = idx.get(api.normalize(pseudo));
            if (id != null) out.add(id);
        }
        return out;
    }

    @Override
    public Optional<UUID> playerOfDiscord(String discordId) {
        if (!api.available() || discordId == null) return Optional.empty();
        Set<UUID> ids = playersOf(discordId, index());
        // un humain peut avoir un compte PC et un compte console : on prend celui qui a un acces au portail
        for (UUID id : ids) if (eco.companies.hasPortalAccess(id).join()) return Optional.of(id);
        return ids.stream().findFirst();
    }

    public record SyncResult(boolean skipped, String reason, int members, int changes) { }

    /** Envoie au bot les metiers de tous les membres Discord lies. A lancer hors du thread serveur. */
    public SyncResult syncJobs() {
        if (!api.available()) return new SyncResult(true, "Citizen Discord n'est pas installé.", 0, 0);
        if (!api.jobRolesEnabled()) return new SyncResult(true, "Les rôles de métier sont désactivés (roles_metiers_actif dans la configuration de Citizen Discord).", 0, 0);
        if (!api.botReady()) return new SyncResult(true, "Le bot Discord n'est pas connecté.", 0, 0);
        Map<String, UUID> idx = index();
        Map<UUID, Set<String>> jobs = eco.companies.jobsByPlayer().join();
        Map<String, Set<String>> byDiscord = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : api.allLinks().entrySet()) {
            Set<String> mine = new TreeSet<>();
            for (UUID id : playersOf(e.getKey(), idx)) mine.addAll(jobs.getOrDefault(id, Set.of()));
            byDiscord.put(e.getKey(), mine); // ensemble vide = retirer les roles de metier
        }
        int changes = api.syncJobs(byDiscord);
        return new SyncResult(false, "", byDiscord.size(), changes);
    }
}
