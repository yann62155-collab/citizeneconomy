package fr.citizen.economy.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Emblemes d'entreprise = metier lu par Citizen Metiers. Un emblème actif restreint les fabrications aux membres de l'entreprise ;
 * "banque" et "aucun" n'imposent aucune restriction. Vide = Citizen Metiers devine le metier d'apres le secteur et le nom.
 */
public final class Emblems {
    public record Emblem(String key, String label, String description) { }

    public static final List<Emblem> ALL = List.of(
            new Emblem("forgeron", "Forgeron", "armures, lettres beuglantes, cartons"),
            new Emblem("menuisier", "Menuisier", "lettres beuglantes, colis, portes à clé, clés"),
            new Emblem("restaurant", "Restaurant", "toute la nourriture"),
            new Emblem("taverne", "Taverne", "toute la nourriture et les boissons"),
            new Emblem("potionniste", "Potionniste", "potions et totems"),
            new Emblem("poissonnier", "Poissonnier", "préparation du poisson"),
            new Emblem("banque", "Banque", "aucune restriction"),
            new Emblem("aucun", "Aucun", "aucune restriction"));

    private Emblems() { }

    /** "" = aucun emblème (détection automatique). Accepte la clé ou le nom, sans tenir compte des accents ni de la casse. */
    public static String parse(String input) {
        String v = norm(input);
        if (v.isEmpty()) return "";
        for (Emblem e : ALL) if (e.key().equals(v) || norm(e.label()).equals(v)) return e.key();
        throw new EconomyException("BAD_EMBLEM", "Emblème inconnu. Choix : " + keys() + ".");
    }

    public static String label(String key) {
        if (key == null || key.isBlank()) return "Non défini (détecté d'après le secteur)";
        for (Emblem e : ALL) if (e.key().equals(key)) return e.label();
        return key; // ancien emblème en texte libre
    }

    public static String keys() {
        StringBuilder sb = new StringBuilder();
        for (Emblem e : ALL) { if (sb.length() > 0) sb.append(", "); sb.append(e.key()); }
        return sb.toString();
    }

    private static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
