package fr.citizen.economy.platform;

import fr.citizen.economy.domain.Actor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Determine, cote serveur uniquement, si un joueur est administrateur de l'economie. */
public final class Admins {
    private Admins() { }

    public static boolean is(ServerPlayer p) {
        try {
            int level = Config.ADMIN_LEVEL.get();
            if (level <= 4 && p.hasPermissions(level)) return true;
            String uuid = p.getUUID().toString();
            String name = p.getGameProfile().getName();
            for (String s : Config.ADMIN_PLAYERS.get()) {
                if (s.equalsIgnoreCase(name) || s.equalsIgnoreCase(uuid)) return true;
            }
        } catch (IllegalStateException configNotLoaded) {
            return p.hasPermissions(4);
        }
        return false;
    }

    public static boolean is(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p && is(p);
    }

    public static Actor actor(ServerPlayer p) {
        return new Actor(p.getUUID(), p.getGameProfile().getName(), is(p));
    }
}
