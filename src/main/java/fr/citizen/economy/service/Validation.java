package fr.citizen.economy.service;

import java.util.regex.Pattern;

final class Validation {
    static final long MAX_AMOUNT = 1_000_000_000_000L;
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N} '&.\\-]{3,32}$");

    private Validation() { }

    static String companyName(String s) {
        String v = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (!NAME.matcher(v).matches())
            throw new EconomyException("BAD_NAME", "Nom invalide : 3 à 32 caractères (lettres, chiffres, espace, ' & . -).");
        return v;
    }

    static String text(String s, int min, int max, String field) {
        String v = s == null ? "" : s.trim();
        if (v.length() < min || v.length() > max)
            throw new EconomyException("BAD_TEXT", field + " : entre " + min + " et " + max + " caractères requis.");
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if ((Character.isISOControl(ch) && ch != '\n') || ch == '\u00a7') throw new EconomyException("BAD_TEXT", field + " : caractères interdits.");
        }
        return v;
    }

    static String optional(String s, int max, String field) {
        return (s == null || s.isBlank()) ? "" : text(s, 0, max, field);
    }

    static long salary(long amount) {
        if (amount < 0 || amount > MAX_AMOUNT)
            throw new EconomyException("BAD_AMOUNT", "Salaire invalide (0 à " + MAX_AMOUNT + ").");
        return amount;
    }
}
