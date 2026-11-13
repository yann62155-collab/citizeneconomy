package fr.citizen.economy.platform;

import fr.citizen.economy.CitizenEconomy;
import fr.citizen.economy.persistence.Database;
import fr.citizen.economy.service.Economy;
import fr.citizen.economy.service.NotificationManager;
import fr.citizen.economy.ui.UiService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Cycle de vie cote serveur : base SQLite, services, thread de travail, taches periodiques. */
public final class EconomyRuntime {
    private static volatile EconomyRuntime current;

    private final MinecraftServer server;
    private final Database db;
    private final Economy economy;
    private final UiService ui;
    private final ExecutorService executor;
    private final fr.citizen.economy.web.WebPanel web;
    private int ticks;
    private volatile boolean schedulerBusy;
    private volatile boolean notifyBusy;

    private EconomyRuntime(MinecraftServer server, Database db) {
        this.server = server;
        this.db = db;
        this.economy = new Economy(db, CitizenEconomy.LOGGER::info);
        this.ui = new UiService(economy, new NeoForgeCash(server), System::currentTimeMillis, HibouBridge.create(server), new ChatBroadcaster(server));
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "CitizenEconomy-worker");
            t.setDaemon(true);
            return t;
        });
        this.web = new fr.citizen.economy.web.WebPanel(ui, economy, new WebConfig(), new fr.citizen.economy.web.LoginCodes(System::currentTimeMillis),
                executor, System::currentTimeMillis, CitizenEconomy.LOGGER::info);
    }

    /** Reglages web lus dans la configuration serveur (valeurs par defaut si elle n'est pas encore chargee). */
    private static final class WebConfig implements fr.citizen.economy.web.WebSettings {
        public boolean enabled() { try { return Config.WEB_ENABLED.get(); } catch (IllegalStateException e) { return true; } }
        public int port() { try { return Config.WEB_PORT.get(); } catch (IllegalStateException e) { return 30039; } }
        public String bindAddress() { try { return Config.WEB_BIND.get(); } catch (IllegalStateException e) { return "0.0.0.0"; } }
        public String councilPassword() { try { return Config.WEB_COUNCIL_PASSWORD.get(); } catch (IllegalStateException e) { return "2007"; } }
    }

    public fr.citizen.economy.web.WebPanel web() { return web; }

    public static String webUrl() {
        try { return Config.WEB_PUBLIC_URL.get(); } catch (IllegalStateException e) { return "http://citizen.boxtoplay.com:30039"; }
    }

    public static EconomyRuntime current() { return current; }

    public ExecutorService executor() { return executor; }
    public UiService ui() { return ui; }
    public Economy economy() { return economy; }

    public static void start(MinecraftServer server) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve(CitizenEconomy.MOD_ID);
        Database db = new Database(dir.resolve("citizeneconomy.db"), dir.resolve("backups"));
        try {
            db.open();
        } catch (Exception e) {
            // On refuse de demarrer plutot que de risquer des donnees economiques corrompues.
            throw new IllegalStateException("Citizen Economy : initialisation SQLite impossible", e);
        }
        current = new EconomyRuntime(server, db);
        CitizenEconomy.LOGGER.info("Citizen Economy : base SQLite prête ({})", dir);
        try {
            current.web.start();
            if ("2007".equals(current.web.councilPasswordForWarning()))
                CitizenEconomy.LOGGER.warn("Panneau web : le mot de passe du Conseil royal est la valeur par défaut (2007). Changez web.councilPassword dans la configuration !");
        } catch (Exception e) {
            CitizenEconomy.LOGGER.error("Panneau web indisponible (port occupé ou non autorisé ?) : {}", e.toString());
        }
    }

    public static void stop() {
        EconomyRuntime rt = current;
        current = null;
        if (rt == null) return;
        rt.web.stop();
        rt.executor.shutdown();
        try { rt.executor.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        rt.db.close();
    }

    /** Appele a chaque tick serveur (thread serveur). */
    public void tick() {
        ticks++;
        if (ticks % 1200 == 0 && !schedulerBusy) { // chaque minute
            schedulerBusy = true;
            executor.execute(() -> {
                try { economy.scheduler.tick(); }
                catch (Throwable t) { CitizenEconomy.LOGGER.error("Planificateur économique", t); }
                finally { schedulerBusy = false; }
            });
        }
        if (ticks % 100 == 0 && !notifyBusy) { // toutes les 5 secondes
            notifyBusy = true;
            executor.execute(() -> {
                try { deliverNotifications(); }
                catch (Throwable t) { CitizenEconomy.LOGGER.error("Notifications", t); }
                finally { notifyBusy = false; }
            });
        }
    }

    private void deliverNotifications() {
        List<UUID> pending = economy.notifications.playersWithPending().join();
        for (UUID id : pending) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue; // restera en attente jusqu'a la prochaine connexion
            List<NotificationManager.Notif> list = economy.notifications.undelivered(id, 10).join();
            if (list.isEmpty()) continue;
            server.execute(() -> {
                for (NotificationManager.Notif n : list)
                    p.sendSystemMessage(Component.literal("[Citizen Economy] ").withStyle(net.minecraft.ChatFormatting.GOLD)
                            .append(Component.literal(n.message()).withStyle(net.minecraft.ChatFormatting.YELLOW)));
            });
            economy.notifications.markDelivered(list.stream().map(NotificationManager.Notif::id).toList()).join();
        }
    }

    public void onLogin(ServerPlayer player) {
        executor.execute(() -> {
            try { economy.players.touch(Admins.actor(player)).join(); }
            catch (Throwable t) { CitizenEconomy.LOGGER.error("Enregistrement du joueur {}", player.getGameProfile().getName(), t); }
        });
    }

    /** Ouvre un ecran pour un joueur (commandes, raccourci). */
    public void open(ServerPlayer player, String view) {
        executor.execute(() -> {
            try { Network.send(player, ui.handle(Admins.actor(player), "open", java.util.Map.of("to", view))); }
            catch (Throwable t) { CitizenEconomy.LOGGER.error("Ouverture de l'écran {}", view, t); }
        });
    }
}
