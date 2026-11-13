package fr.citizen.economy.platform;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** Configuration serveur (fichier world/serverconfig/citizeneconomy-server.toml). */
public final class Config {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue ADMIN_LEVEL;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ADMIN_PLAYERS;
    public static final ModConfigSpec.BooleanValue CHAT_PUBLIC_ANNOUNCEMENTS;
    public static final ModConfigSpec.BooleanValue HUD_PUBLIC_ANNOUNCEMENTS;
    public static final ModConfigSpec.BooleanValue WEB_ENABLED;
    public static final ModConfigSpec.IntValue WEB_PORT;
    public static final ModConfigSpec.ConfigValue<String> WEB_BIND;
    public static final ModConfigSpec.ConfigValue<String> WEB_COUNCIL_PASSWORD;
    public static final ModConfigSpec.ConfigValue<String> WEB_PUBLIC_URL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Administration de Citizen Economy").push("admin");
        ADMIN_LEVEL = b.comment("Niveau de permission vanilla (op) donnant accès à l'administration économique. 4 = opérateurs complets, 5 = personne par ce moyen.")
                .defineInRange("adminPermissionLevel", 4, 0, 5);
        ADMIN_PLAYERS = b.comment("Pseudos ou UUID supplémentaires autorisés a administrer l'économie (en plus du niveau ci-dessus).")
                .defineListAllowEmpty("adminPlayers", List.of(), () -> "", o -> o instanceof String);
        b.pop();
        b.comment("Chat").push("chat");
        CHAT_PUBLIC_ANNOUNCEMENTS = b.comment("Affiche dans le chat de tous les joueurs chaque annonce publique publiée par une entreprise.")
                .define("publicAnnouncementsInChat", true);
        HUD_PUBLIC_ANNOUNCEMENTS = b.comment("Affiche chaque annonce publique pendant 15 secondes à gauche de l'écran de tous les joueurs (entreprise, titre, texte).")
                .define("publicAnnouncementsHud", true);
        b.pop();
        b.comment("Panneau web de gestion des entreprises").push("web");
        WEB_ENABLED = b.comment("Active le panneau web.").define("enabled", true);
        WEB_PORT = b.comment("Port TCP du panneau web (à ouvrir chez l'hébergeur).").defineInRange("port", 30039, 1024, 65535);
        WEB_BIND = b.comment("Adresse d'écoute (0.0.0.0 = toutes les interfaces).").define("bindAddress", "0.0.0.0");
        WEB_COUNCIL_PASSWORD = b.comment("Mot de passe du Conseil royal (patron de toutes les entreprises). CHANGEZ-LE : la valeur par défaut est connue. Vide = accès Conseil désactivé.")
                .define("councilPassword", "2007");
        WEB_PUBLIC_URL = b.comment("Adresse affichée aux joueurs par /ceconomy web.").define("publicUrl", "http://citizen.boxtoplay.com:30039");
        b.pop();
        SPEC = b.build();
    }

    private Config() { }
}
