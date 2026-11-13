package fr.citizen.economy.domain;
import java.util.UUID;
public record Company(long id, String uid, String name, String emblem, String description, String sector,
                      long createdAt, UUID owner, UUID manager, CompanyStatus status, String statusReason,
                      boolean announcementsSuspended, String matricule) {
    /** Matricule officiel : E-0001. */
    public static String matriculeOf(long id) { return String.format("E-%04d", id); }

    /** Normalise une saisie ("e-3", "0003", "E-0003") en matricule, ou null si ce n'en est pas un. */
    public static String parseMatricule(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.startsWith("E-")) t = t.substring(2);
        else if (t.startsWith("E")) t = t.substring(1);
        if (t.isEmpty() || t.length() > 9 || !t.chars().allMatch(ch -> ch >= '0' && ch <= '9')) return null;
        return matriculeOf(Long.parseLong(t));
    }
}
