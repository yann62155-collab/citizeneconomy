package fr.citizen.economy.web;

/** Reglages du panneau web, relus a chaque usage (le mot de passe peut donc changer sans redemarrage). */
public interface WebSettings {
    boolean enabled();
    int port();
    String bindAddress();
    /** Mot de passe du Conseil royal (null ou vide = acces Conseil desactive). */
    String councilPassword();

    /** Connexion avec Discord (OAuth2) : active seulement si l'identifiant et le secret de l'application sont renseignes. */
    default boolean discordEnabled() { return false; }
    default String discordClientId() { return ""; }
    default String discordClientSecret() { return ""; }
    /** Adresse de retour declaree dans le portail developpeur Discord (ex : http://mc312.boxtoplay.com:30039/auth/discord/callback). */
    default String discordRedirectUri() { return ""; }
}
