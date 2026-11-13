package fr.citizen.economy.platform;

import fr.citizen.economy.CitizenEconomy;
import fr.citizen.economy.ui.HibouDirectory;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Lien optionnel avec le mod Hibou Postal (id « hiboupostal »), par réflexion : Citizen Economy n'en dépend pas
 * à la compilation et fonctionne sans lui. Les données du hibou ne sont lues que sur le thread serveur.
 */
final class HibouBridge implements HibouDirectory {
    private static final String DATA_CLASS = "fr.citizen.hiboupostal.data.OwlPostData";

    private final MinecraftServer server;
    private final Method get;
    private final Method findByNumber;
    private final Method getNumber;

    private HibouBridge(MinecraftServer server, Method get, Method findByNumber, Method getNumber) {
        this.server = server;
        this.get = get;
        this.findByNumber = findByNumber;
        this.getNumber = getNumber;
    }

    static HibouDirectory create(MinecraftServer server) {
        try {
            if (!ModList.get().isLoaded("hiboupostal")) return HibouDirectory.NONE;
            Class<?> data = Class.forName(DATA_CLASS);
            HibouDirectory d = new HibouBridge(server, data.getMethod("get", MinecraftServer.class),
                    data.getMethod("findByNumber", int.class), data.getMethod("getNumber", UUID.class));
            CitizenEconomy.LOGGER.info("Citizen Economy : mod Hibou Postal détecté, les factures aux joueurs utilisent le numéro de hibou.");
            return d;
        } catch (Throwable t) {
            CitizenEconomy.LOGGER.warn("Citizen Economy : Hibou Postal présent mais inutilisable ({}), les pseudos sont utilisés.", t.toString());
            return HibouDirectory.NONE;
        }
    }

    @Override
    public boolean available() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<UUID> findByNumber(int number) {
        return onServer(() -> {
            try {
                Object data = get.invoke(null, server);
                return (Optional<UUID>) findByNumber.invoke(data, number);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public int numberOf(UUID player) {
        return onServer(() -> {
            try {
                return (Integer) getNumber.invoke(get.invoke(null, server), player);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private <T> T onServer(Supplier<T> task) {
        if (server.isSameThread()) return task.get();
        return CompletableFuture.supplyAsync(task, server).join();
    }
}
