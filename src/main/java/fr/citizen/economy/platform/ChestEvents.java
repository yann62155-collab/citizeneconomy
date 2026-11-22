package fr.citizen.economy.platform;

import fr.citizen.economy.CitizenEconomy;
import fr.citizen.economy.service.ChestLocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.List;

/**
 * Coffres d'entreprise : un coffre ordinaire marque au nom d'une entreprise. Seuls les membres de l'entreprise l'ouvrent ;
 * seuls le patron et les gerants (et les admins) peuvent le casser. Aucun bloc ni objet nouveau : les joueurs console n'ont rien a installer.
 * Limites assumees : un entonnoir pose par un membre, un piston ou un autre mod peuvent encore deplacer le contenu.
 */
public final class ChestEvents {
    static final String TAG = "citizeneconomy_company";

    private ChestEvents() { }

    // ------------------------------------------------------------------ l'objet

    public static ItemStack item(long companyId, String companyName) {
        ItemStack s = new ItemStack(Items.CHEST);
        CompoundTag t = new CompoundTag();
        t.putLong(TAG, companyId);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Coffre de " + companyName).withStyle(ChatFormatting.GOLD));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Réservé aux membres de l'entreprise").withStyle(ChatFormatting.GRAY))));
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    static Long companyOf(ItemStack s) {
        if (s.isEmpty() || !s.is(Items.CHEST)) return null;
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return null;
        CompoundTag t = d.copyTag();
        return t.contains(TAG, Tag.TAG_LONG) ? t.getLong(TAG) : null;
    }

    // ------------------------------------------------------------------ utilitaires

    private static String dim(Level lv) { return lv.dimension().location().toString(); }

    /** Entreprise qui possede le coffre a cette position, en comptant l'autre moitie d'un grand coffre. */
    private static Long lockedCompany(ChestLocks locks, Level lv, BlockPos pos) {
        String d = dim(lv);
        Long c = locks.companyAt(d, pos.getX(), pos.getY(), pos.getZ());
        if (c != null) return c;
        BlockState st = lv.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos o = pos.relative(ChestBlock.getConnectedDirection(st));
            return locks.companyAt(d, o.getX(), o.getY(), o.getZ());
        }
        return null;
    }

    private static boolean canOpen(ChestLocks l, ServerPlayer p, long company) { return Admins.is(p) || l.isMember(p.getUUID(), company); }
    private static boolean canManage(ChestLocks l, ServerPlayer p, long company) { return Admins.is(p) || l.isStaff(p.getUUID(), company); }

    private static void say(ServerPlayer p, String text) { p.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.RED), true); }

    private static void register(EconomyRuntime rt, ServerPlayer p, Level lv, BlockPos pos, long company) {
        String d = dim(lv);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        rt.locks().add(d, x, y, z, company);
        rt.economy().chests.register(Admins.actor(p), company, d, x, y, z).whenComplete((id, err) -> {
            if (err == null) return;
            rt.locks().remove(d, x, y, z);
            CitizenEconomy.LOGGER.warn("Coffre d'entreprise non enregistré : {}", err.toString());
            p.server.execute(() -> say(p, "Coffre d'entreprise non enregistré : " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage())));
        });
    }

    // ------------------------------------------------------------------ evenements

    public static void onUse(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) return;
        Long co = lockedCompany(rt.locks(), p.level(), e.getPos());
        if (co == null || canOpen(rt.locks(), p, co)) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.FAIL);
        say(p, "Ce coffre appartient à « " + rt.locks().name(co) + " » : vous n'y avez pas accès.");
    }

    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof Level lv) || lv.isClientSide()) return;
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) return;
        ChestLocks locks = rt.locks();
        BlockPos pos = e.getPos();
        Block b = e.getPlacedBlock().getBlock();

        if (b instanceof ChestBlock) {
            Long tagged = companyOf(p.getMainHandItem());
            if (tagged == null) tagged = companyOf(p.getOffhandItem());
            if (tagged != null) { // coffre d'entreprise donne par un admin
                if (!canManage(locks, p, tagged)) {
                    e.setCanceled(true);
                    say(p, "Seul le patron ou un gérant de « " + locks.name(tagged) + " » peut poser ce coffre.");
                    return;
                }
                register(rt, p, lv, pos, tagged);
                return;
            }
            // coffre ordinaire colle a un coffre d'entreprise : il en deviendrait la moitie
            Long near = lockedCompany(locks, lv, pos);
            if (near != null) {
                if (!canManage(locks, p, near)) {
                    e.setCanceled(true);
                    say(p, "Vous ne pouvez pas agrandir le coffre de « " + locks.name(near) + " ».");
                } else register(rt, p, lv, pos, near);
            }
            return;
        }
        if (b == Blocks.HOPPER) { // un entonnoir pourrait vider un coffre voisin
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                Long co = lockedCompany(locks, lv, pos.relative(d));
                if (co != null && !canManage(locks, p, co)) {
                    e.setCanceled(true);
                    say(p, "Impossible de poser un entonnoir contre le coffre de « " + locks.name(co) + " ».");
                    return;
                }
            }
        }
    }

    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof Level lv) || lv.isClientSide()) return;
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) return;
        BlockPos pos = e.getPos();
        Long co = lockedCompany(rt.locks(), lv, pos);
        if (co == null) return;
        if (!canManage(rt.locks(), p, co)) {
            e.setCanceled(true);
            say(p, "Seul le patron ou un gérant de « " + rt.locks().name(co) + " » peut détruire ce coffre.");
            return;
        }
        String d = dim(lv);
        if (rt.locks().companyAt(d, pos.getX(), pos.getY(), pos.getZ()) != null) {
            rt.locks().remove(d, pos.getX(), pos.getY(), pos.getZ());
            rt.economy().chests.remove(Admins.actor(p), d, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public static void onExplosion(ExplosionEvent.Detonate e) {
        EconomyRuntime rt = EconomyRuntime.current();
        Level lv = e.getLevel();
        if (rt == null || lv.isClientSide()) return;
        e.getAffectedBlocks().removeIf(pos -> lockedCompany(rt.locks(), lv, pos) != null);
    }
}
