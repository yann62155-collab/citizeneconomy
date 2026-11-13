package fr.citizen.economy.platform;

import fr.citizen.economy.CitizenEconomy;
import fr.citizen.economy.domain.Actor;
import fr.citizen.economy.ui.View;
import fr.citizen.economy.ui.ViewCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Map;
import java.util.function.Consumer;

/** Enregistrement des paquets et aiguillage. Aucun import client ici : ce code est charge aussi sur serveur dedie. */
public final class Network {
    public static final String VERSION = "2";

    /** Renseigne cote client uniquement (ClientSetup). */
    public static volatile Consumer<String> clientViewHandler;

    public static volatile Consumer<AnnouncementHudPayload> clientHudHandler;

    private Network() { }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(VERSION);
        r.playToClient(ViewPayload.TYPE, ViewPayload.CODEC, Network::onView);
        r.playToClient(AnnouncementHudPayload.TYPE, AnnouncementHudPayload.CODEC, (p, ctx) -> {
            Consumer<AnnouncementHudPayload> h = clientHudHandler;
            if (h != null) h.accept(p);
        });
        r.playToServer(ActionPayload.TYPE, ActionPayload.CODEC, Network::onAction);
    }

    private static void onView(ViewPayload payload, IPayloadContext ctx) {
        Consumer<String> h = clientViewHandler;
        if (h != null) h.accept(payload.json());
    }

    private static void onAction(ActionPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) return;
        String action = payload.action();
        String argsJson = payload.argsJson();
        // Les services sont bloquants : on quitte le thread serveur pour un thread de travail.
        rt.executor().execute(() -> {
            Actor actor = Admins.actor(player);
            View view;
            try {
                Map<String, String> args = ViewCodec.argsFromJson(argsJson);
                view = rt.ui().handle(actor, action, args);
            } catch (IllegalArgumentException bad) {
                CitizenEconomy.LOGGER.warn("Requête invalide de {} : {}", actor.name(), bad.getMessage());
                return;
            } catch (Throwable t) {
                CitizenEconomy.LOGGER.error("Erreur UI pour {}", actor.name(), t);
                return;
            }
            send(player, view);
        });
    }

    public static void send(ServerPlayer player, View view) {
        PacketDistributor.sendToPlayer(player, new ViewPayload(ViewCodec.toJson(view)));
    }
}
