package fr.citizen.economy.platform;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce que Citizen Economy utilise de Citizen Discord. Pas de dependance de compilation : le mod Discord est appele par
 * reflexion (comme Citizen Metiers appelle Citizen Economy) et son absence est un cas normal.
 */
public interface DiscordApiFacade {
    boolean available();
    boolean botReady();
    boolean jobRolesEnabled();
    /** identifiant Discord -> pseudos Minecraft lies (PC et console) */
    Map<String, List<String>> allLinks();
    /** normalisation de la whitelist : minuscules, sans prefixe console */
    String normalize(String pseudo);
    /** identifiant Discord -> cles de metier ; renvoie le nombre de roles modifies */
    int syncJobs(Map<String, Set<String>> jobsByDiscordId);

    DiscordApiFacade NONE = new DiscordApiFacade() {
        public boolean available() { return false; }
        public boolean botReady() { return false; }
        public boolean jobRolesEnabled() { return false; }
        public Map<String, List<String>> allLinks() { return Map.of(); }
        public String normalize(String pseudo) { return pseudo == null ? "" : pseudo.trim().toLowerCase(java.util.Locale.ROOT); }
        public int syncJobs(Map<String, Set<String>> jobsByDiscordId) { return 0; }
    };

    /** Facade reelle si Citizen Discord (1.4.0+) est installe, sinon NONE. */
    static DiscordApiFacade reflective() { return reflective("fr.citizen.discord.api.DiscordApi"); }

    static DiscordApiFacade reflective(String className) {
        final Class<?> api;
        try { api = Class.forName(className); }
        catch (ClassNotFoundException | LinkageError e) { return NONE; }
        return new DiscordApiFacade() {
            private Object call(String name, Class<?>[] types, Object... args) {
                try {
                    Method m = api.getMethod(name, types);
                    return m.invoke(null, args);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IllegalStateException("Citizen Discord : " + name + " indisponible (" + e + ")", e);
                }
            }
            public boolean available() { return true; }
            public boolean botReady() { return (boolean) call("botReady", new Class<?>[0]); }
            public boolean jobRolesEnabled() { return (boolean) call("jobRolesEnabled", new Class<?>[0]); }
            @SuppressWarnings("unchecked")
            public Map<String, List<String>> allLinks() { return (Map<String, List<String>>) call("allLinks", new Class<?>[0]); }
            public String normalize(String pseudo) { return (String) call("normalize", new Class<?>[]{String.class}, pseudo); }
            public int syncJobs(Map<String, Set<String>> jobs) { return (int) call("syncJobs", new Class<?>[]{Map.class}, jobs); }
        };
    }
}
