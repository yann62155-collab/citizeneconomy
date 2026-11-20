package fr.citizen.economy.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Emblemes d'entreprise = metier lu par Citizen Metiers. Un emblème actif restreint les fabrications aux membres de l'entreprise ;
 * BAN et AUC n'imposent aucune restriction. Vide = Citizen Metiers devine le metier d'apres le secteur et le nom.
 */
public final class Emblems {
    public record Emblem(String key, String name, String label, String description) { }

    public static final List<Emblem> ALL = List.of(
            new Emblem("FOR", "forgeron", "Forgeron", "armures, lettres beuglantes, cartons"),
            new Emblem("MEN", "menuisier", "Menuisier", "lettres beuglantes, colis, portes à clé, clés"),
            new Emblem("RES", "restaurant", "Restaurant", "toute la nourriture"),
            new Emblem("TAV", "taverne", "Taverne", "toute la nourriture et les boissons"),
            new Emblem("POT", "potionniste", "Potionniste", "potions et totems"),
            new Emblem("POI", "poissonnier", "Poissonnier", "préparation du poisson"),
            new Emblem("HOP", "hopital", "Hôpital", "soins des blessés réservés à ses membres (Citizen Médical)"),
            new Emblem("BAN", "banque", "Banque", "aucune restriction"),
            new Emblem("AUC", "aucun", "Aucun", "aucune restriction"));

    private Emblems() { }

    /** "" = aucun emblème (détection automatique). Accepte la clé ou le nom, sans tenir compte des accents ni de la casse. */
    public static String parse(String input) {
        String v = norm(input);
        if (v.isEmpty()) return "";
        for (Emblem e : ALL) if (norm(e.key()).equals(v) || e.name().equals(v) || norm(e.label()).equals(v)) return e.key();
        throw new EconomyException("BAD_EMBLEM", "Emblème inconnu. Codes : " + keys() + ".");
    }

    public static String label(String key) {
        if (key == null || key.isBlank()) return "Non défini (détecté d'après le secteur)";
        String k = norm(key);
        for (Emblem e : ALL) if (norm(e.key()).equals(k) || e.name().equals(k)) return e.label() + " (" + e.key() + ")";
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
