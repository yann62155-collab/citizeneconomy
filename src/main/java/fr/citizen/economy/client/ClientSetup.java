package fr.citizen.economy.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.citizen.economy.CitizenEconomy;
import fr.citizen.economy.platform.ActionPayload;
import fr.citizen.economy.platform.Network;
import fr.citizen.economy.ui.View;
import fr.citizen.economy.ui.ViewCodec;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Initialisation cote client uniquement (classe chargee seulement quand Dist == CLIENT). */
public final class ClientSetup {
    public static final KeyMapping OPEN_KEY = new KeyMapping("key.citizeneconomy.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.citizeneconomy");

    private ClientSetup() { }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN_KEY));
        modBus.addListener(AnnouncementHud::register);
        Network.clientHudHandler = p -> Minecraft.getInstance().execute(() -> AnnouncementHud.push(p));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_KEY.consumeClick()) {
                if (mc.player != null && mc.screen == null)
                    PacketDistributor.sendToServer(new ActionPayload("open", "{\"to\":\"main\"}"));
            }
        });
        Network.clientViewHandler = json -> {
            View view;
            try { view = ViewCodec.fromJson(json); }
            catch (RuntimeException ex) { CitizenEconomy.LOGGER.warn("Écran reçu invalide : {}", ex.getMessage()); return; }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof ViewScreen vs) vs.update(view);
                else mc.setScreen(new ViewScreen(view));
            });
        };
    }
}
