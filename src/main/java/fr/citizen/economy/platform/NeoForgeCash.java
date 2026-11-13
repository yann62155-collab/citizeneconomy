package fr.citizen.economy.platform;

import fr.citizen.economy.ui.CashPlatform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Pont avec l'inventaire reel du joueur. Toutes les operations tournent sur le thread serveur. */
public final class NeoForgeCash implements CashPlatform {
    private final MinecraftServer server;

    public NeoForgeCash(MinecraftServer server) { this.server = server; }

    @Override
    public <T> T onServer(Supplier<T> task) {
        if (server.isSameThread()) return task.get();
        return CompletableFuture.supplyAsync(task, server).join();
    }

    private ServerPlayer find(UUID id) { return server.getPlayerList().getPlayer(id); }

    @Override
    public long[] countCoins(UUID player) {
        long[] out = new long[4];
        ServerPlayer p = find(player);
        if (p == null) return out;
        Inventory inv = p.getInventory();
        for (int d = 0; d < 4; d++) {
            Item coin = ModItems.byDenomination(d);
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(coin)) out[d] += s.getCount();
            }
        }
        return out;
    }

    @Override
    public void removeCoins(UUID player, long[] counts) {
        ServerPlayer p = find(player);
        if (p == null) throw new IllegalStateException("joueur hors ligne");
        Inventory inv = p.getInventory();
        for (int d = 0; d < 4; d++) {
            long left = counts[d];
            Item coin = ModItems.byDenomination(d);
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.is(coin)) continue;
                int take = (int) Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
            if (left > 0) throw new IllegalStateException("pièces manquantes");
        }
        p.containerMenu.broadcastChanges();
    }

    @Override
    public boolean giveCoins(UUID player, long[] counts) {
        ServerPlayer p = find(player);
        if (p == null) return false;
        for (int d = 0; d < 4; d++) {
            long left = counts[d];
            Item coin = ModItems.byDenomination(d);
            while (left > 0) {
                int n = (int) Math.min(left, coin.getDefaultMaxStackSize());
                ItemStack stack = new ItemStack(coin, n);
                left -= n;
                if (!p.getInventory().add(stack) && !stack.isEmpty()) p.drop(stack, false); // inventaire plein : tombe au sol
            }
        }
        p.containerMenu.broadcastChanges();
        return true;
    }
}
