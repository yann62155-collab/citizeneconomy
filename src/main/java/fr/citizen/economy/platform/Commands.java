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
        root.then(open("treasury", "treasury", true));
        root.then(open("admin", "admin", true));
        root.then(net.minecraft.commands.Commands.literal("web").executes(c -> web(c.getSource())));
        root.then(net.minecraft.commands.Commands.literal("help").executes(c -> help(c.getSource())));
        d.register(root);
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
            try { owner = rt.web().ownsCompany(p.getUUID()); } catch (RuntimeException e) { owner = false; }
            final boolean ok = owner;
            p.server.execute(() -> {
                if (!ok) { p.sendSystemMessage(Component.literal("Le panneau web est réservé aux patrons : vous ne détenez aucune entreprise.")); return; }
                String code = rt.web().codes().issue(p.getUUID(), p.getGameProfile().getName());
                p.sendSystemMessage(Component.literal("Panneau des entreprises : " + EconomyRuntime.webUrl()));
                p.sendSystemMessage(Component.literal("Votre code de connexion : " + code + " (valable 5 minutes, à usage unique)"));
            });
        });
        return 1;
    }

    private static int help(CommandSourceStack src) {
        String[] lines = {
                "/ceconomy : menu principal (touche K)",
                "/ceconomy bank : banque et monnaie",
                "/ceconomy company : mes entreprises",
                "/ceconomy create : demander la création d'une entreprise",
                "/ceconomy announcements : annonces publiques",
                "/ceconomy web : code de connexion au panneau web des entreprises (patrons)",
                "/ceconomy treasury : trésorerie royale (admin)",
                "/ceconomy admin : administration (admin)",
        };
        for (String l : lines) src.sendSuccess(() -> Component.literal(l).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }
}
