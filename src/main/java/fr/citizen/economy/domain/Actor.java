package fr.citizen.economy.domain;
import java.util.UUID;
/** Auteur d'une action, determine cote serveur (jamais fourni par le client). */
public record Actor(UUID id, String name, boolean admin) {
    /** Identite reservee du Conseil royal (panneau web) : patron de toutes les entreprises, sans etre administrateur. */
    public static final UUID COUNCIL_ID = UUID.fromString("00000000-0000-4000-8000-00000000c0c1");

    public static Actor council() { return new Actor(COUNCIL_ID, "Conseil royal", true); }

    public boolean isCouncil() { return COUNCIL_ID.equals(id); }
}
