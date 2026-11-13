package fr.citizen.economy.client;

import fr.citizen.economy.CitizenEconomy;
import fr.citizen.economy.platform.AnnouncementHudPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.ArrayDeque;
import java.util.List;

/** Affichage temporaire (15 s) à gauche de l'écran : entreprise en tête, puis titre, puis texte. Les annonces se suivent. */
public final class AnnouncementHud {
    static final long DURATION_MS = 15_000L;
    static final long FADE_MS = 700L;
    static final int WIDTH = 170;
    static final int MAX_QUEUE = 5;

    private static final ArrayDeque<AnnouncementHudPayload> QUEUE = new ArrayDeque<>();
    private static AnnouncementHudPayload current;
    private static long shownAt;

    private AnnouncementHud() { }

    /** Appelé depuis le thread client. */
    public static void push(AnnouncementHudPayload p) {
        if (current == null) { current = p; shownAt = System.currentTimeMillis(); }
        else { if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst(); QUEUE.addLast(p); }
    }

    public static void register(RegisterGuiLayersEvent e) {
        e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(CitizenEconomy.MOD_ID, "announcement_hud"), AnnouncementHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        if (current == null) return;
        long now = System.currentTimeMillis();
        long age = now - shownAt;
        if (age >= DURATION_MS) {
            current = QUEUE.pollFirst();
            shownAt = now;
            if (current == null) return;
            age = 0;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Font font = mc.font;
        int inner = WIDTH - 14;
        List<FormattedCharSequence> company = font.split(Component.literal(current.company()).withStyle(ChatFormatting.BOLD), inner);
        List<FormattedCharSequence> title = font.split(Component.literal(current.title()).withStyle(ChatFormatting.BOLD), inner);
        List<FormattedCharSequence> body = font.split(FormattedText.of(current.body()), inner);
        if (company.size() > 2) company = company.subList(0, 2);
        if (title.size() > 3) title = title.subList(0, 3);
        if (body.size() > 9) body = body.subList(0, 9);

        int lh = font.lineHeight + 1;
        int h = 8 + company.size() * lh + 5 + title.size() * lh + 5 + body.size() * lh + 6;
        int x = 6;
        int y = Math.max(6, (g.guiHeight() - h) / 2);

        float alpha = 1f;
        if (age < FADE_MS) alpha = age / (float) FADE_MS;
        else if (age > DURATION_MS - FADE_MS) alpha = (DURATION_MS - age) / (float) FADE_MS;
        alpha = Math.max(0.05f, Math.min(1f, alpha));
        int a = (int) (alpha * 255) << 24;

        // fond noir, filet vieil or, barre rouge sombre
        g.fill(x, y, x + WIDTH, y + h, (int) (alpha * 0xE0) << 24 | 0x120C0C);
        g.fill(x, y, x + WIDTH, y + 1, a | 0xB8912F);
        g.fill(x, y + h - 1, x + WIDTH, y + h, a | 0xB8912F);
        g.fill(x, y, x + 1, y + h, a | 0xB8912F);
        g.fill(x + WIDTH - 1, y, x + WIDTH, y + h, a | 0xB8912F);
        g.fill(x + 1, y + 1, x + 4, y + h - 1, a | 0x7A1010);

        int tx = x + 9;
        int ty = y + 6;
        for (FormattedCharSequence l : company) { g.drawString(font, l, tx, ty, a | 0xD4AF37, false); ty += lh; }
        ty += 1;
        g.fill(tx, ty, x + WIDTH - 7, ty + 1, a | 0x7A1010);
        ty += 4;
        for (FormattedCharSequence l : title) { g.drawString(font, l, tx, ty, a | 0xFFFFFF, false); ty += lh; }
        ty += 5;
        for (FormattedCharSequence l : body) { g.drawString(font, l, tx, ty, a | 0xE8DCC0, false); ty += lh; }
    }
}
