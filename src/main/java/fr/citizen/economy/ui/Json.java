package fr.citizen.economy.ui;

import java.util.*;

/** Mini JSON (sans dependance) : suffisant pour les vues et les actions. Le parseur est borne (profondeur, taille). */
public final class Json {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_LEN = 400_000;

    private Json() { }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o);
        return sb.toString();
    }

    private static void w(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) str(sb, s);
        else if (o instanceof Boolean || o instanceof Number) sb.append(o);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                w(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                w(sb, x);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("type JSON non gere : " + o.getClass());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    /** @return Map, List, String, Long, Double, Boolean ou null. */
    public static Object parse(String s) {
        if (s == null || s.length() > MAX_LEN) throw new IllegalArgumentException("JSON trop long");
        Parser p = new Parser(s);
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("JSON : donnees apres la valeur");
        return v;
    }

    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        char peek() { if (i >= s.length()) throw new IllegalArgumentException("JSON tronque"); return s.charAt(i); }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON trop profond");
            ws();
            char c = peek();
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (peek() != ':') throw new IllegalArgumentException("JSON : ':' attendu");
                i++;
                m.put(k, value(depth + 1));
                ws();
                char c = peek(); i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("JSON : ',' attendu");
                if (m.size() > 1000) throw new IllegalArgumentException("JSON : trop d'entrees");
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value(depth + 1));
                ws();
                char c = peek(); i++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("JSON : ',' attendu");
                if (l.size() > 5000) throw new IllegalArgumentException("JSON : trop d'elements");
            }
        }

        String string() {
            if (peek() != '"') throw new IllegalArgumentException("JSON : chaine attendue");
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek(); i++;
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = peek(); i++;
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("JSON : \\u tronque");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e); // " \ /
                }
            }
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("JSON : valeur inattendue");
            String t = s.substring(st, i);
            try { return t.contains(".") || t.contains("e") || t.contains("E") ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("JSON : nombre invalide"); }
        }
    }
}
