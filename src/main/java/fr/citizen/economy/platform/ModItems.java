package fr.citizen.economy.platform;

import fr.citizen.economy.CitizenEconomy;
import fr.citizen.economy.service.Coins;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Pieces physiques : bronze 1, argent 10, or 100, platine 1000 PO. */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CitizenEconomy.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CitizenEconomy.MOD_ID);

    public static final DeferredItem<Item> BRONZE = ITEMS.registerSimpleItem("coin_bronze", new Item.Properties().stacksTo(64));
    public static final DeferredItem<Item> SILVER = ITEMS.registerSimpleItem("coin_silver", new Item.Properties().stacksTo(64));
    public static final DeferredItem<Item> GOLD = ITEMS.registerSimpleItem("coin_gold", new Item.Properties().stacksTo(64).rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> PLATINUM = ITEMS.registerSimpleItem("coin_platinum", new Item.Properties().stacksTo(64).rarity(Rarity.RARE));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.citizeneconomy"))
            .icon(() -> new ItemStack(GOLD.get()))
            .displayItems((params, out) -> {
                out.accept(BRONZE.get());
                out.accept(SILVER.get());
                out.accept(GOLD.get());
                out.accept(PLATINUM.get());
            }).build());

    private ModItems() { }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        TABS.register(bus);
    }

    /** Index = ordinal de {@link Coins.Denomination} (platine, or, argent, bronze). */
    public static Item byDenomination(int ordinal) {
        return switch (Coins.Denomination.values()[ordinal]) {
            case PLATINE -> PLATINUM.get();
            case OR -> GOLD.get();
            case ARGENT -> SILVER.get();
            case BRONZE -> BRONZE.get();
        };
    }
}
