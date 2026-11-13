package fr.citizen.economy.web;

/** Reglages du panneau web, relus a chaque usage (le mot de passe peut donc changer sans redemarrage). */
public interface WebSettings {
    boolean enabled();
    int port();
    String bindAddress();
    /** Mot de passe du Conseil royal (null ou vide = acces Conseil desactive). */
    String councilPassword();
}
