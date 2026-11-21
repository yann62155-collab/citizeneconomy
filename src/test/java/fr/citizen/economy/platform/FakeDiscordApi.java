package fr.citizen.economy.platform;

import java.util.*;

/** Fausse API Citizen Discord pour tester l'appel par reflexion. */
public final class FakeDiscordApi {
    public static final List<Map<String, Set<String>>> synced = new ArrayList<>();
    public static boolean ready = true, rolesOn = true;
    public static Map<String, List<String>> links = new LinkedHashMap<>();

    public static boolean botReady() { return ready; }
    public static boolean jobRolesEnabled() { return rolesOn; }
    public static Map<String, List<String>> allLinks() { return links; }
    public static String normalize(String p) { String n = p.trim(); if (n.startsWith(".")) n = n.substring(1); return n.replace(' ', '_').toLowerCase(Locale.ROOT); }
    public static int syncJobs(Map<String, Set<String>> jobs) { synced.add(jobs); return 3; }
}
