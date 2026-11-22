package fr.citizen.economy.platform;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /ceconomy et sous-commandes. Les commandes ouvrent des ecrans ; chaque action est de toute facon reverifiee par le serveur. */
public final class Commands {
    private Commands() { }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = net.minecraft.commands.Commands.literal("ceconomy")
                .executes(c -> open(c.getSource(), "main"));
        root.then(open("company", "companies", false));
        root.then(open("create", "create", false));
        root.then(open("bank", "bank", false));
        root.then(open("announcements", "board", false));
        root.then(net.minecraft.commands.Commands.literal("recensement").executes(c -> census(c.getSource())));
        root.then(open("treasury", "treasury", true));
        root.then(open("admin", "admin", true));
        root.then(net.minecraft.commands.Commands.literal("web").executes(c -> web(c.getSource())));
        root.then(net.minecraft.commands.Commands.literal("discord").requires(Admins::is).executes(c -> discord(c.getSource())));
        root.then(net.minecraft.commands.Commands.literal("chest").requires(Admins::is).then(net.minecraft.commands.Commands.literal("give")
                .then(net.minecraft.commands.Commands.argument("joueur", net.minecraft.commands.arguments.EntityArgument.player())
                        .then(net.minecraft.commands.Commands.argument("entreprise", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(c -> giveChest(c.getSource(), net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "joueur"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "entreprise")))))));
        root.then(net.minecraft.commands.Commands.literal("help").executes(c -> help(c.getSource())));
        d.register(root);
        // Recensement des citoyens : reserve aux employes de la couronne (et aux admins)
        d.register(net.minecraft.commands.Commands.literal("recensement").executes(c -> census(c.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> open(String name, String view, boolean adminOnly) {
        LiteralArgumentBuilder<CommandSourceStack> b = net.minecraft.commands.Commands.literal(name);
        if (adminOnly) b.requires(Admins::is);
        return b.executes(c -> open(c.getSource(), view));
    }

    private static int open(CommandSourceStack src, String view) {
        if (!(src.getEntity() instanceof ServerPlayer p)) {
            src.sendFailure(Component.literal("Commande réservée aux joueurs."));
            return 0;
        }
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) {
            src.sendFailure(Component.literal("L'économie n'est pas disponible."));
            return 0;
        }
        rt.open(p, view);
        return 1;
    }

    private static int census(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer p)) {
            src.sendFailure(Component.literal("Commande réservée aux joueurs."));
            return 0;
        }
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) {
            src.sendFailure(Component.literal("L'économie n'est pas disponible."));
            return 0;
        }
        rt.executor().execute(() -> {
            boolean ok;
            try { ok = rt.economy().census.canCensus(Admins.actor(p)).join(); } catch (RuntimeException e) { ok = false; }
            final boolean allowed = ok;
            p.server.execute(() -> {
                if (!allowed) p.sendSystemMessage(Component.literal("Le recensement est réservé aux employés de la couronne. Présentez-vous à l'un d'eux pour être recensé.")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                else rt.open(p, "adm_census");
            });
        });
        return 1;
    }

    private static int web(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer p)) {
            src.sendFailure(Component.literal("Commande réservée aux joueurs."));
            return 0;
        }
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null || rt.web().port() < 0) {
            src.sendFailure(Component.literal("Le panneau web n'est pas disponible."));
            return 0;
        }
        rt.executor().execute(() -> {
            boolean owner;
            try { owner = rt.web().canUsePortal(p.getUUID()); } catch (RuntimeException e) { owner = false; }
            final boolean ok = owner;
            p.server.execute(() -> {
                if (!ok) { p.sendSystemMessage(Component.literal("Le panneau web est réservé aux patrons, gérants et employés qui ont des droits de caisse, stock, comptabilité ou finances.")); return; }
                String code = rt.web().codes().issue(p.getUUID(), p.getGameProfile().getName());
                p.sendSystemMessage(Component.literal("Panneau des entreprises : " + EconomyRuntime.webUrl()));
                p.sendSystemMessage(Component.literal("Votre code de connexion : " + code + " (valable 5 minutes, à usage unique)"));
            });
        });
        return 1;
    }

    private static int discord(CommandSourceStack src) {
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) { src.sendFailure(Component.literal("L'économie n'est pas disponible.")); return 0; }
        src.sendSuccess(() -> Component.literal("Envoi des métiers à Discord en cours…").withStyle(ChatFormatting.GOLD), false);
        rt.executor().execute(() -> {
            String msg;
            try { msg = rt.syncDiscordNow(); } catch (Throwable t) { msg = "Échec : " + t; }
            final String m = msg;
            src.getServer().execute(() -> src.sendSuccess(() -> Component.literal(m).withStyle(ChatFormatting.YELLOW), false));
        });
        return 1;
    }

    private static int giveChest(CommandSourceStack src, ServerPlayer target, String company) {
        EconomyRuntime rt = EconomyRuntime.current();
        if (rt == null) { src.sendFailure(Component.literal("L'économie n'est pas disponible.")); return 0; }
        rt.executor().execute(() -> {
            fr.citizen.economy.service.ChestManager.CompanyRef ref;
            try { ref = rt.economy().chests.resolveActive(company).join(); }
            catch (RuntimeException e) {
                String m = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                src.getServer().execute(() -> src.sendFailure(Component.literal(m)));
                return;
            }
            src.getServer().execute(() -> {
                net.minecraft.world.item.ItemStack stack = ChestEvents.item(ref.id(), ref.name());
                if (!target.getInventory().add(stack)) target.drop(stack, false);
                src.sendSuccess(() -> Component.literal("Coffre de « " + ref.name() + " » donné à " + target.getGameProfile().getName() + ".").withStyle(ChatFormatting.GREEN), true);
                target.sendSystemMessage(Component.literal("Vous avez reçu un coffre d'entreprise (" + ref.name() + "). Posez-le : seuls les membres de l'entreprise pourront l'ouvrir."));
            });
        });
        return 1;
    }

    private static int help(CommandSourceStack src) {
        String[] lines = {
                "/ceconomy : menu principal (touche K)",
                "/ceconomy bank : banque et monnaie",
                "/ceconomy company : mes entreprises",
                "/recensement : recenser les citoyens (réservé aux employés de la couronne et aux admins)",
                "/ceconomy create : demander la création d'une entreprise",
                "/ceconomy announcements : annonces publiques",
                "/ceconomy web : code de connexion au panneau web (patrons, gérants, employés avec droits)",
                "/ceconomy treasury : trésorerie royale (admin)",
                "/ceconomy discord : envoyer maintenant les métiers au bot Discord (admin)",
                "/ceconomy chest give <joueur> <entreprise> : donne un coffre d'entreprise (admin)",
                "/ceconomy admin : administration (admin)",
        };
        for (String l : lines) src.sendSuccess(() -> Component.literal(l).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }
}
