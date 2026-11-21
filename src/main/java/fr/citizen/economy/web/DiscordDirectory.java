package fr.citizen.economy.web;

import java.util.Optional;
import java.util.UUID;

/** Lien entre un compte Discord et un joueur Minecraft (fourni par Citizen Discord : commande /pseudo). */
public interface DiscordDirectory {
    DiscordDirectory NONE = id -> Optional.empty();

    Optional<UUID> playerOfDiscord(String discordId);
}
