package fr.citizen.economy.client;

import fr.citizen.economy.platform.ActionPayload;
import fr.citizen.economy.ui.View;
import fr.citizen.economy.ui.ViewCodec;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Ecran generique pilote par le serveur, style medieval-fantasy (noir, or ancien, rouge sombre, parchemin).
 * Dessin "immediat" : seuls les champs de saisie sont de vrais widgets, boutons et lignes sont dessines a la main.
 * Le client ne decide de rien : il affiche la vue recue et renvoie l'action cliquee.
 */
public final class ViewScreen extends Screen {
    private static final int BLACK = 0xFF0E0A08, GOLD = 0xFFB8892B, GOLD_LIGHT = 0xFFF2D27A, RED = 0xFF7A1414, RED_HOVER = 0xFF9B1C1C,
            PARCH = 0xFFE9D8AE, INK = 0xFF2B1B0E;

    private record Rect(int x, int y, int w, int h) {
        boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private record Hit(Rect r, View.Btn btn) { }
    private static final class Row {
        List<FormattedCharSequence> text = List.of();
        int color = INK, height, textW;
        boolean header;
        List<View.Btn> btns = List.of();
    }

    private View view;
    private final Map<String, EditBox> boxes = new LinkedHashMap<>();
    private final Map<String, String> remembered = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> bottomHits = new ArrayList<>();
    private int panelX, panelY, panelW, panelH, bodyTop, bodyBottom, contentH, scroll;
    private View.Btn pendingConfirm;
    private boolean waiting;
    private long waitingSince;

    public ViewScreen(View v) {
        super(Component.literal(v.title()));
        this.view = v;
    }

    public void update(View v) {
        if (v.id().equals(view.id()) && "err".equals(v.kind())) { // garde la saisie pour corriger une erreur
            for (Map.Entry<String, EditBox> e : boxes.entrySet()) remembered.put(e.getKey(), e.getValue().getValue());
        } else {
            remembered.clear();
            if (!v.id().equals(view.id())) scroll = 0;
        }
        this.view = v;
        this.waiting = false;
        this.pendingConfirm = null;
        rebuild();
    }

    @Override
    protected void init() { rebuild(); }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ mise en page

    private void rebuild() {
        clearWidgets();
        boxes.clear();
        rows.clear();
        Font font = this.font;
        panelW = Math.min(width - 16, 440);
        panelH = height - 16;
        panelX = (width - panelW) / 2;
        panelY = 8;
        int inner = panelW - 24;

        // zone basse : boutons (qui passent a la ligne) puis champs
        int btnRows = 0, cx = 0;
        for (View.Btn b : view.buttons()) {
            int w = font.width(b.label()) + 14;
            if (cx > 0 && cx + w > inner) { btnRows++; cx = 0; }
            cx += w + 4;
        }
        if (!view.buttons().isEmpty()) btnRows++;
        int buttonsH = btnRows * 20;
        int nf = view.fields().size();
        int fieldCols = nf > 3 ? 2 : 1;
        int fieldRows = (nf + fieldCols - 1) / fieldCols;
        int fieldsH = fieldRows * 30;
        int bottomTop = panelY + panelH - 8 - buttonsH;
        int fieldsTop = bottomTop - (nf > 0 ? fieldsH + 4 : 0);

        int msgH = view.message().isEmpty() ? 0 : 16;
        bodyTop = panelY + 30 + msgH;
        bodyBottom = fieldsTop - 4;

        // champs
        int colW = (inner - (fieldCols - 1) * 8) / fieldCols;
        for (int i = 0; i < nf; i++) {
            View.Field f = view.fields().get(i);
            int col = i % fieldCols, row = i / fieldCols;
            int fx = panelX + 12 + col * (colW + 8);
            int fy = fieldsTop + row * 30 + 11;
            EditBox eb = new EditBox(font, fx, fy, colW, 16, Component.literal(f.label()));
            eb.setMaxLength(Math.max(1, f.max()));
            eb.setValue(remembered.getOrDefault(f.id(), f.value()));
            addRenderableWidget(eb);
            boxes.put(f.id(), eb);
        }

        // lignes defilantes
        contentH = 0;
        for (View.Line l : view.lines()) {
            Row r = new Row();
            r.header = l.style().equals("h");
            r.color = switch (l.style()) {
                case "g" -> 0xFF7A5300; case "r" -> 0xFF8B1A1A; case "m" -> 0xFF6E5A44; case "h" -> 0xFF5C0F0F; default -> INK;
            };
            r.btns = l.btns();
            int btnsW = 0;
            for (View.Btn b : r.btns) btnsW += font.width(b.label()) + 12;
            if (!r.btns.isEmpty()) btnsW += 2 * r.btns.size();
            r.textW = Math.max(60, inner - 8 - btnsW);
            r.text = l.text().isEmpty() ? List.of() : font.split(Component.literal(l.text()), r.textW);
            int textH = Math.max(1, r.text.size()) * 10;
            r.height = Math.max(r.text.isEmpty() ? 6 : textH, r.btns.isEmpty() ? 0 : 16) + (r.header ? 6 : 2);
            rows.add(r);
            contentH += r.height;
        }
        clampScroll();
    }

    private void clampScroll() {
        int max = Math.max(0, contentH - (bodyBottom - bodyTop));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    // ------------------------------------------------------------------ dessin

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xB0000000);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (waiting && System.currentTimeMillis() - waitingSince > 4000) waiting = false;
        renderBackground(g, mx, my, pt);
        hits.clear();
        bottomHits.clear();
        Font font = this.font;

        // cadre
        g.fill(panelX - 3, panelY - 3, panelX + panelW + 3, panelY + panelH + 3, BLACK);
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, GOLD);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PARCH);
        g.fill(panelX, panelY, panelX + panelW, panelY + 6, 0x22000000);
        g.fill(panelX, panelY + panelH - 6, panelX + panelW, panelY + panelH, 0x22000000);
        g.fill(panelX, panelY, panelX + 5, panelY + panelH, 0x18000000);
        g.fill(panelX + panelW - 5, panelY, panelX + panelW, panelY + panelH, 0x18000000);

        // bandeau noir + titre + couronne
        g.fill(panelX, panelY, panelX + panelW, panelY + 26, BLACK);
        g.fill(panelX, panelY + 26, panelX + panelW, panelY + 28, RED);
        g.fill(panelX, panelY + 28, panelX + panelW, panelY + 29, GOLD);
        crown(g, panelX + 10, panelY + 6);
        g.drawString(font, view.title(), panelX + 34, panelY + 9, GOLD_LIGHT, true);

        // message
        if (!view.message().isEmpty()) {
            int col = switch (view.kind()) { case "ok" -> 0xFF2F5D1E; case "err" -> 0xFF9B1B1B; default -> 0xFF5C4A2E; };
            String m = font.plainSubstrByWidth(view.message(), panelW - 28);
            g.fill(panelX + 8, panelY + 31, panelX + panelW - 8, panelY + 46, 0x33000000);
            g.fill(panelX + 8, panelY + 31, panelX + 11, panelY + 46, col);
            g.drawString(font, m, panelX + 16, panelY + 35, col, false);
            if (font.width(view.message()) > panelW - 28 && mx >= panelX && mx < panelX + panelW && my >= panelY + 31 && my < panelY + 46)
                g.renderTooltip(font, font.split(Component.literal(view.message()), 300), mx, my);
        }

        // lignes
        g.enableScissor(panelX + 4, bodyTop, panelX + panelW - 4, bodyBottom);
        int y = bodyTop - scroll;
        boolean blocked = pendingConfirm != null;
        for (Row r : rows) {
            if (y + r.height >= bodyTop && y <= bodyBottom) {
                int ty = y + (r.header ? 4 : 1);
                int tx = panelX + 12;
                for (FormattedCharSequence seq : r.text) { g.drawString(font, seq, tx, ty, r.color, false); ty += 10; }
                if (r.header) g.fill(panelX + 12, y + r.height - 2, panelX + panelW - 12, y + r.height - 1, GOLD);
                int bx = panelX + panelW - 12;
                for (int i = r.btns.size() - 1; i >= 0; i--) {
                    View.Btn b = r.btns.get(i);
                    int w = font.width(b.label()) + 12;
                    bx -= w;
                    Rect rect = new Rect(bx, y + 1, w, 14);
                    boolean visible = rect.y() >= bodyTop - 2 && rect.y() + rect.h() <= bodyBottom + 2;
                    drawButton(g, rect, b.label(), !blocked && visible && my >= bodyTop && my < bodyBottom && rect.in(mx, my));
                    if (visible) hits.add(new Hit(rect, b));
                    bx -= 2;
                }
            }
            y += r.height;
        }
        g.disableScissor();
        if (contentH > bodyBottom - bodyTop) { // barre de defilement
            int trackH = bodyBottom - bodyTop;
            int thumbH = Math.max(14, trackH * trackH / contentH);
            int thumbY = bodyTop + (int) ((long) (trackH - thumbH) * scroll / Math.max(1, contentH - trackH));
            g.fill(panelX + panelW - 8, bodyTop, panelX + panelW - 5, bodyBottom, 0x33000000);
            g.fill(panelX + panelW - 8, thumbY, panelX + panelW - 5, thumbY + thumbH, RED);
        }

        // libelles des champs
        int nf = view.fields().size();
        int fieldCols = nf > 3 ? 2 : 1;
        int inner = panelW - 24;
        int colW = (inner - (fieldCols - 1) * 8) / fieldCols;
        int fieldsTop = bodyBottom + 4;
        for (int i = 0; i < nf; i++) {
            View.Field f = view.fields().get(i);
            int col = i % fieldCols, row = i / fieldCols;
            int fx = panelX + 12 + col * (colW + 8);
            g.drawString(font, font.plainSubstrByWidth(f.label(), colW), fx, fieldsTop + row * 30 + 1, 0xFF5C0F0F, false);
        }

        // boutons du bas
        int cx = panelX + 12, cy = panelY + panelH - 8 - ((countBtnRows(font, inner)) * 20) + 2;
        for (View.Btn b : view.buttons()) {
            int w = font.width(b.label()) + 14;
            if (cx > panelX + 12 && cx + w > panelX + 12 + inner) { cx = panelX + 12; cy += 20; }
            Rect rect = new Rect(cx, cy, w, 16);
            drawButton(g, rect, b.label(), !blocked && rect.in(mx, my));
            bottomHits.add(new Hit(rect, b));
            cx += w + 4;
        }

        for (EditBox eb : boxes.values()) eb.render(g, mx, my, pt); // champs de saisie (pas de super.render : il redessinerait le fond)

        if (waiting) g.drawString(font, "…", panelX + panelW - 20, panelY + 9, GOLD_LIGHT, true);
        if (pendingConfirm != null) renderConfirm(g, mx, my);
    }

    private int countBtnRows(Font font, int inner) {
        int rowsN = 0, cx = 0;
        for (View.Btn b : view.buttons()) {
            int w = font.width(b.label()) + 14;
            if (cx > 0 && cx + w > inner) { rowsN++; cx = 0; }
            cx += w + 4;
        }
        return view.buttons().isEmpty() ? 0 : rowsN + 1;
    }

    private void drawButton(GuiGraphics g, Rect r, String label, boolean hover) {
        g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), BLACK);
        g.fill(r.x() + 1, r.y() + 1, r.x() + r.w() - 1, r.y() + r.h() - 1, GOLD);
        g.fill(r.x() + 2, r.y() + 2, r.x() + r.w() - 2, r.y() + r.h() - 2, hover ? RED_HOVER : RED);
        int tw = font.width(label);
        g.drawString(font, label, r.x() + (r.w() - tw) / 2, r.y() + (r.h() - 8) / 2, hover ? 0xFFFFFFFF : GOLD_LIGHT, false);
    }

    /** Couronne dessinee par rectangles (aucune texture necessaire). */
    private void crown(GuiGraphics g, int x, int y) {
        int c = 0xFFD4A537, d = 0xFF8A6414;
        g.fill(x, y + 10, x + 16, y + 14, c);
        g.fill(x, y + 14, x + 16, y + 15, d);
        g.fill(x, y + 3, x + 3, y + 10, c);
        g.fill(x + 6, y + 1, x + 10, y + 10, c);
        g.fill(x + 13, y + 3, x + 16, y + 10, c);
        g.fill(x + 1, y + 1, x + 3, y + 3, 0xFFB01E1E);
        g.fill(x + 7, y - 1, x + 9, y + 1, 0xFFB01E1E);
        g.fill(x + 13, y + 1, x + 15, y + 3, 0xFFB01E1E);
    }

    private Rect yesRect, noRect;

    private void renderConfirm(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, height, 0xA0000000);
        int w = Math.min(280, width - 20);
        List<FormattedCharSequence> txt = font.split(Component.literal(pendingConfirm.confirm()), w - 24);
        int h = 40 + txt.size() * 10 + 8;
        int x = (width - w) / 2, y = (height - h) / 2;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, BLACK);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, GOLD);
        g.fill(x, y, x + w, y + h, PARCH);
        g.fill(x, y, x + w, y + 14, BLACK);
        g.drawString(font, "Confirmation", x + 8, y + 3, GOLD_LIGHT, false);
        int ty = y + 20;
        for (FormattedCharSequence s : txt) { g.drawString(font, s, x + 12, ty, INK, false); ty += 10; }
        yesRect = new Rect(x + w / 2 - 70, y + h - 24, 64, 16);
        noRect = new Rect(x + w / 2 + 6, y + h - 24, 64, 16);
        drawButton(g, yesRect, "Confirmer", yesRect.in(mx, my));
        drawButton(g, noRect, "Annuler", noRect.in(mx, my));
    }

    // ------------------------------------------------------------------ interactions

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        if (pendingConfirm != null) {
            if (yesRect != null && yesRect.in(mx, my)) { View.Btn b = pendingConfirm; pendingConfirm = null; send(b); }
            else if (noRect != null && noRect.in(mx, my)) pendingConfirm = null;
            return true;
        }
        for (Hit h : bottomHits) if (h.r().in(mx, my)) { fire(h.btn()); return true; }
        if (my >= bodyTop && my < bodyBottom) for (Hit h : hits) if (h.r().in(mx, my)) { fire(h.btn()); return true; }
        return super.mouseClicked(mx, my, button);
    }

    private void fire(View.Btn b) {
        if (waiting) return;
        if (b.confirm() != null && !b.confirm().isEmpty()) pendingConfirm = b;
        else send(b);
    }

    private void send(View.Btn b) {
        Map<String, String> args = new LinkedHashMap<>(b.args());
        if (!b.action().equals("open")) {
            for (Map.Entry<String, EditBox> e : boxes.entrySet()) args.put(e.getKey(), e.getValue().getValue());
            args.put("_n", view.nonce());
        } else {
            args.put("_v", view.id());
        }
        waiting = true;
        waitingSince = System.currentTimeMillis();
        PacketDistributor.sendToServer(new ActionPayload(b.action(), ViewCodec.argsToJson(args)));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (pendingConfirm != null) return true;
        scroll -= (int) (sy * 14);
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (pendingConfirm != null) {
            if (key == 256) { pendingConfirm = null; return true; }
            return true;
        }
        if (boxes.values().stream().noneMatch(EditBox::isFocused)) {
            switch (key) {
                case 264 -> { scroll += 14; clampScroll(); return true; }  // bas
                case 265 -> { scroll -= 14; clampScroll(); return true; }  // haut
                case 266 -> { scroll -= 80; clampScroll(); return true; }  // page haut
                case 267 -> { scroll += 80; clampScroll(); return true; }  // page bas
                default -> { }
            }
        }
        return super.keyPressed(key, scan, mods);
    }
}
