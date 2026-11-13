package fr.citizen.economy.ui;

import java.util.*;

/** Construction fluide d'une vue. Les boutons d'action memorisent la vue courante pour pouvoir la rafraichir. */
final class ViewBuilder {
    private static final int MAX_LINES = 180;

    private final String id;
    private final String title;
    private final Map<String, String> params;
    private final List<View.Line> lines = new ArrayList<>();
    private final List<View.Field> fields = new ArrayList<>();
    private final List<View.Btn> buttons = new ArrayList<>();
    private String message = "";
    private String kind = "info";

    ViewBuilder(String id, String title, Map<String, String> params) {
        this.id = id; this.title = title; this.params = params;
    }

    ViewBuilder message(String m, String k) { if (m != null && !m.isBlank()) { message = m; kind = k; } return this; }

    ViewBuilder line(String text, String style, View.Btn... btns) {
        if (lines.size() < MAX_LINES) lines.add(new View.Line(text, style, List.of(btns)));
        return this;
    }
    ViewBuilder h(String t) { return line(t, "h"); }
    ViewBuilder n(String t, View.Btn... b) { return line(t, "n", b); }
    ViewBuilder g(String t, View.Btn... b) { return line(t, "g", b); }
    ViewBuilder r(String t, View.Btn... b) { return line(t, "r", b); }
    ViewBuilder m(String t, View.Btn... b) { return line(t, "m", b); }
    ViewBuilder blank() { return line("", "n"); }

    ViewBuilder field(String fid, String label, String value, int max) {
        fields.add(new View.Field(fid, label, value == null ? "" : value, max));
        return this;
    }

    /** Bouton d'action : fusionne les parametres de la vue, ceux fournis (cle, valeur, ...) et la vue a rafraichir. */
    View.Btn act(String label, String action, String... kv) {
        Map<String, String> a = new LinkedHashMap<>(params);
        for (int i = 0; i + 1 < kv.length; i += 2) a.put(kv[i], kv[i + 1]);
        a.put("_v", id);
        return new View.Btn(label, action, a, null);
    }

    /** Bouton de navigation vers une autre vue. */
    static View.Btn nav(String label, String view, String... kv) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("to", view);
        for (int i = 0; i + 1 < kv.length; i += 2) a.put(kv[i], kv[i + 1]);
        return new View.Btn(label, "open", a, null);
    }

    static View.Btn confirm(View.Btn b, String text) { return new View.Btn(b.label(), b.action(), b.args(), text); }

    ViewBuilder button(View.Btn b) { buttons.add(b); return this; }

    View build() {
        return new View(id, title, UUID.randomUUID().toString(), message, kind, lines, fields, buttons);
    }
}
