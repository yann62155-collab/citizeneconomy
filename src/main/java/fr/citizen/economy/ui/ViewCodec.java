package fr.citizen.economy.ui;

import java.util.*;

/** Serialisation des vues (serveur -> client) et des arguments d'action (client -> serveur, NON fiables). */
public final class ViewCodec {
    private ViewCodec() { }

    public static String toJson(View v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id()); m.put("title", v.title()); m.put("nonce", v.nonce());
        m.put("message", v.message()); m.put("kind", v.kind());
        List<Object> lines = new ArrayList<>();
        for (View.Line l : v.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("t", l.text()); lm.put("s", l.style());
            List<Object> bs = new ArrayList<>();
            for (View.Btn b : l.btns()) bs.add(btn(b));
            lm.put("b", bs);
            lines.add(lm);
        }
        m.put("lines", lines);
        List<Object> fields = new ArrayList<>();
        for (View.Field f : v.fields()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", f.id()); fm.put("label", f.label()); fm.put("value", f.value()); fm.put("max", f.max());
            fields.add(fm);
        }
        m.put("fields", fields);
        List<Object> bs = new ArrayList<>();
        for (View.Btn b : v.buttons()) bs.add(btn(b));
        m.put("buttons", bs);
        return Json.write(m);
    }

    private static Map<String, Object> btn(View.Btn b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("l", b.label()); m.put("a", b.action()); m.put("args", new LinkedHashMap<>(b.args()));
        m.put("c", b.confirm());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static View fromJson(String json) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(json);
        List<View.Line> lines = new ArrayList<>();
        for (Object o : (List<Object>) m.get("lines")) {
            Map<String, Object> lm = (Map<String, Object>) o;
            List<View.Btn> bs = new ArrayList<>();
            for (Object b : (List<Object>) lm.get("b")) bs.add(btnFrom((Map<String, Object>) b));
            lines.add(new View.Line(str(lm.get("t")), str(lm.get("s")), bs));
        }
        List<View.Field> fields = new ArrayList<>();
        for (Object o : (List<Object>) m.get("fields")) {
            Map<String, Object> fm = (Map<String, Object>) o;
            fields.add(new View.Field(str(fm.get("id")), str(fm.get("label")), str(fm.get("value")), ((Number) fm.get("max")).intValue()));
        }
        List<View.Btn> buttons = new ArrayList<>();
        for (Object b : (List<Object>) m.get("buttons")) buttons.add(btnFrom((Map<String, Object>) b));
        return new View(str(m.get("id")), str(m.get("title")), str(m.get("nonce")), str(m.get("message")), str(m.get("kind")), lines, fields, buttons);
    }

    @SuppressWarnings("unchecked")
    private static View.Btn btnFrom(Map<String, Object> b) {
        Map<String, String> args = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) b.get("args")).entrySet()) args.put(e.getKey(), str(e.getValue()));
        return new View.Btn(str(b.get("l")), str(b.get("a")), args, b.get("c") == null ? null : str(b.get("c")));
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    // ------------------------------------------------------------------ arguments d'action (client -> serveur)

    public static String argsToJson(Map<String, String> args) { return Json.write(args); }

    /** Parse defensif : taille, nombre et longueur des cles/valeurs bornes ; tout est converti en texte. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> argsFromJson(String json) {
        Object o;
        try { o = Json.parse(json); } catch (RuntimeException e) { throw new IllegalArgumentException("arguments invalides"); }
        if (!(o instanceof Map<?, ?> m) || m.size() > 40) throw new IllegalArgumentException("arguments invalides");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            String k = e.getKey();
            if (k.length() > 32 || e.getValue() instanceof Map || e.getValue() instanceof List) throw new IllegalArgumentException("arguments invalides");
            String v = e.getValue() == null ? "" : e.getValue().toString();
            if (v.length() > 1100) throw new IllegalArgumentException("arguments invalides");
            out.put(k, v);
        }
        return out;
    }
}
