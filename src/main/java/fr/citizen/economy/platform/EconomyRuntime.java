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
    private volatile boolean censusBusy;

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
        this.discord = new DiscordLinks(economy, DiscordApiFacade.reflective());
        this.web.setDiscord(new fr.citizen.economy.web.DiscordOAuth(new WebConfig()), discord);
    }

    private final DiscordLinks discord;
    private final fr.citizen.economy.service.ChestLocks locks = new fr.citizen.economy.service.ChestLocks();
    private volatile boolean chestBusy;

    public fr.citizen.economy.service.ChestLocks locks() { return locks; }
    private volatile boolean discordBusy;

    public DiscordLinks discord() { return discord; }

    /** Envoie les metiers au bot Discord (thread de travail). Renvoie un message lisible. */
    public String syncDiscordNow() {
        DiscordLinks.SyncResult r = discord.syncJobs();
        return r.skipped() ? r.reason() : "Métiers envoyés à Discord : " + r.members() + " compte(s) vérifié(s), " + r.changes() + " rôle(s) modifié(s).";
    }

    /** Reglages web lus dans la configuration serveur (valeurs par defaut si elle n'est pas encore chargee). */
    private static final class WebConfig implements fr.citizen.economy.web.WebSettings {
        public boolean enabled() { try { return Config.WEB_ENABLED.get(); } catch (IllegalStateException e) { return true; } }
        public int port() { try { return Config.WEB_PORT.get(); } catch (IllegalStateException e) { return 30039; } }
        public String bindAddress() { try { return Config.WEB_BIND.get(); } catch (IllegalStateException e) { return "0.0.0.0"; } }
        public String councilPassword() { try { return Config.WEB_COUNCIL_PASSWORD.get(); } catch (IllegalStateException e) { return "2007"; } }
        public boolean discordEnabled() { return !discordClientId().isBlank() && !discordClientSecret().isBlank(); }
        public String discordClientId() { try { return Config.DISCORD_CLIENT_ID.get().trim(); } catch (IllegalStateException e) { return ""; } }
        public String discordClientSecret() { try { return Config.DISCORD_CLIENT_SECRET.get().trim(); } catch (IllegalStateException e) { return ""; } }
        public String discordRedirectUri() {
            try {
                String v = Config.DISCORD_REDIRECT_URI.get().trim();
                if (!v.isEmpty()) return v;
            } catch (IllegalStateException ignored) { }
            String base = webUrl();
            return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/auth/discord/callback";
        }
    }

    public fr.citizen.economy.web.WebPanel web() { return web; }

    public static String webUrl() {
        try { return Config.WEB_PUBLIC_URL.get(); } catch (IllegalStateException e) { return "http://mc312.boxtoplay.com:30039"; }
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
        try {
            current.locks.loadChests(current.economy.chests.all().join());
            current.locks.loadMembers(current.economy.chests.snapshot().join());
        } catch (Throwable t) { CitizenEconomy.LOGGER.error("Chargement des coffres d'entreprise", t); }
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
        fr.citizen.economy.api.Recensement.clear();
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
        int syncMin;
        try { syncMin = Config.DISCORD_SYNC_MINUTES.get(); } catch (IllegalStateException e) { syncMin = 0; }
        if (syncMin > 0 && (ticks == 2400 || ticks % (1200L * syncMin) == 0) && !discordBusy && discord.available()) { // 2 min apres le demarrage, puis periodiquement
            discordBusy = true;
            executor.execute(() -> {
                try { String m = syncDiscordNow(); if (!m.startsWith("Les rôles") && !m.startsWith("Le bot") && !m.startsWith("Citizen Discord")) CitizenEconomy.LOGGER.info(m); }
                catch (Throwable t) { CitizenEconomy.LOGGER.warn("Envoi des métiers à Discord : {}", t.toString()); }
                finally { discordBusy = false; }
            });
        }
        if (ticks % 100 == 70 && !chestBusy) { // membres des entreprises : toutes les 5 secondes
            chestBusy = true;
            executor.execute(() -> {
                try { locks.loadMembers(economy.chests.snapshot().join()); }
                catch (Throwable t) { CitizenEconomy.LOGGER.error("Membres des coffres d'entreprise", t); }
                finally { chestBusy = false; }
            });
        }
        if ((ticks == 20 || ticks % 100 == 50) && !censusBusy) { // recensement : toutes les 5 secondes
            censusBusy = true;
            executor.execute(() -> {
                try { refreshCensus(); }
                catch (Throwable t) { CitizenEconomy.LOGGER.error("Recensement", t); }
                finally { censusBusy = false; }
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

    /** Recopie le recensement dans l'API publique et rafraichit la liste Tab des joueurs dont la fiche a change. */
    private void refreshCensus() {
        java.util.Map<UUID, fr.citizen.economy.service.CensusManager.Card> cards = economy.census.cards().join();
        java.util.Map<UUID, String[]> fresh = new java.util.HashMap<>();
        cards.forEach((id, c) -> fresh.put(id, new String[]{c.firstName(), c.lastName(), Integer.toString(c.age()), c.profession(), c.validated() ? "1" : "0"}));
        java.util.Set<UUID> changed = fr.citizen.economy.api.Recensement.replaceAll(fresh);
        if (changed.isEmpty()) return;
        server.execute(() -> {
            for (UUID id : changed) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) p.refreshTabListName();
            }
        });
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
            try {
                economy.players.touch(Admins.actor(player)).join();
                boolean validated = economy.census.get(player.getUUID()).join().map(e -> e.validated()).orElse(false);
                if (!validated) server.execute(() -> remindCensus(player));
            }
            catch (Throwable t) { CitizenEconomy.LOGGER.error("Enregistrement du joueur {}", player.getGameProfile().getName(), t); }
        });
    }

    private static void remindCensus(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("[Recensement] ").withStyle(net.minecraft.ChatFormatting.GOLD)
                .append(Component.literal("Le royaume ne vous a pas encore recensé. Présentez-vous à un employé de la couronne.").withStyle(net.minecraft.ChatFormatting.YELLOW)));
    }

    /** Ouvre un ecran pour un joueur (commandes, raccourci). */
    public void open(ServerPlayer player, String view) {
        executor.execute(() -> {
            try { Network.send(player, ui.handle(Admins.actor(player), "open", java.util.Map.of("to", view))); }
            catch (Throwable t) { CitizenEconomy.LOGGER.error("Ouverture de l'écran {}", view, t); }
        });
    }
}
