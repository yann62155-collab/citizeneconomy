package fr.citizen.economy.platform;

import fr.citizen.economy.CitizenEconomy;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Serveur -> client : annonce publique à afficher 15 s à gauche de l'écran. */
public record AnnouncementHudPayload(String company, String title, String body) implements CustomPacketPayload {
    public static final Type<AnnouncementHudPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CitizenEconomy.MOD_ID, "announcement_hud"));
    public static final StreamCodec<ByteBuf, AnnouncementHudPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(80), AnnouncementHudPayload::company,
            ByteBufCodecs.stringUtf8(120), AnnouncementHudPayload::title,
            ByteBufCodecs.stringUtf8(1000), AnnouncementHudPayload::body,
            AnnouncementHudPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
