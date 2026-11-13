package fr.citizen.economy.platform;

import fr.citizen.economy.CitizenEconomy;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> serveur : action demandee. Rien de ce paquet n'est cru : l'identite et les droits viennent du serveur. */
public record ActionPayload(String action, String argsJson) implements CustomPacketPayload {
    public static final Type<ActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CitizenEconomy.MOD_ID, "action"));
    public static final StreamCodec<ByteBuf, ActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), ActionPayload::action,
            ByteBufCodecs.stringUtf8(8_192), ActionPayload::argsJson,
            ActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
