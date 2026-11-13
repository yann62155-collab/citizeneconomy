package fr.citizen.economy.ui;

/** Diffusion dans le chat du serveur (implémentée côté NeoForge ; rien à faire dans les tests). */
public interface Broadcaster {
    /** Une annonce publique vient d'être publiée par une entreprise. */
    void publicAnnouncement(String companyName, String category, String title, String body);

    Broadcaster NONE = (company, category, title, body) -> { };
}
