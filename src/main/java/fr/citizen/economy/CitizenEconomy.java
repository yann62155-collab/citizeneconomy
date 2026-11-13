package fr.citizen.economy;

import com.mojang.logging.LogUtils;
import fr.citizen.economy.platform.Commands;
import fr.citizen.economy.platform.Config;
import fr.citizen.economy.platform.EconomyRuntime;
import fr.citizen.economy.platform.ModItems;
import fr.citizen.economy.platform.Network;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(CitizenEconomy.MOD_ID)
public class CitizenEconomy {
    public static final String MOD_ID = "citizeneconomy";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CitizenEconomy(IEventBus modBus, ModContainer container) {
        ModItems.register(modBus);
        modBus.addListener(Network::register);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onLogin);

        if (FMLEnvironment.dist == Dist.CLIENT) fr.citizen.economy.client.ClientSetup.init(modBus);
    }

    private void onCommands(RegisterCommandsEvent e) { Commands.register(e.getDispatcher()); }

    private void onServerStarting(ServerStartingEvent e) { EconomyRuntime.start(e.getServer()); }

    private void onServerStopped(ServerStoppedEvent e) { EconomyRuntime.stop(); }

    private void onTick(ServerTickEvent.Post e) {
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt != null) rt.tick();
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt != null && e.getEntity() instanceof ServerPlayer p) rt.onLogin(p);
    }
}
