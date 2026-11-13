package fr.citizen.economy.platform;

import fr.citizen.economy.ui.Broadcaster;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;

/** Diffuse les annonces publiques dans le chat de tous les joueurs connectés. */
final class ChatBroadcaster implements Broadcaster {
    private final MinecraftServer server;

    ChatBroadcaster(MinecraftServer server) { this.server = server; }

    @Override
    public void publicAnnouncement(String companyName, String category, String title, String body) {
        boolean chat = true, hud = true;
        try { chat = Config.CHAT_PUBLIC_ANNOUNCEMENTS.get(); } catch (IllegalStateException configNotLoaded) { /* défaut : on diffuse */ }
        try { hud = Config.HUD_PUBLIC_ANNOUNCEMENTS.get(); } catch (IllegalStateException configNotLoaded) { /* défaut : on diffuse */ }
        if (hud) {
            AnnouncementHudPayload payload = new AnnouncementHudPayload(companyName, title, body);
            server.execute(() -> net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(payload));
        }
        if (!chat) return;
        MutableComponent msg = Component.literal("[Annonce] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(companyName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" — " + category + " : ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(title).withStyle(ChatFormatting.WHITE))
                .withStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ceconomy announcements"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Cliquez pour ouvrir le panneau des annonces"))));
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(msg, false));
    }
}
