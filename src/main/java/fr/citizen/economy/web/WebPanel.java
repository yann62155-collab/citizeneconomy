package fr.citizen.economy.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.citizen.economy.domain.Actor;
import fr.citizen.economy.domain.Company;
import fr.citizen.economy.service.Economy;
import fr.citizen.economy.ui.Json;
import fr.citizen.economy.ui.UiService;
import fr.citizen.economy.ui.View;
import fr.citizen.economy.ui.ViewCodec;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Panneau web de gestion des entreprises (port 30039 par defaut). Memes ecrans et memes services que le mod en jeu :
 * le navigateur n'affiche que ce que le serveur decrit et renvoie l'action cliquee, rien n'est cru cote client.
 * Deux acces : le patron (code a usage unique obtenu en jeu) et le Conseil royal (mot de passe, patron de toutes les entreprises).
 */
public final class WebPanel {
    static final long SESSION_MS = 2 * 60 * 60_000L;
    static final int MAX_SESSIONS = 200;
    static final int MAX_BODY = 16 * 1024;
    static final int MAX_FAILS = 5;
    static final long FAIL_WINDOW_MS = 10 * 60_000L;

    private record Session(Actor actor, boolean council, long[] expiresAt) { }

    private final UiService ui;
    private final Economy eco;
    private final WebSettings settings;
    private final LoginCodes codes;
    private final Executor worker;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Long>> fails = new ConcurrentHashMap<>();
    private final SecureRandom rnd = new SecureRandom();
    private HttpServer server;
    private ExecutorService httpPool;
    private byte[] page;

    public WebPanel(UiService ui, Economy eco, WebSettings settings, LoginCodes codes, Executor worker, LongSupplier clock, Consumer<String> log) {
        this.ui = ui; this.eco = eco; this.settings = settings; this.codes = codes; this.worker = worker; this.clock = clock; this.log = log;
    }

    public LoginCodes codes() { return codes; }

    /** Le joueur detient-il au moins une entreprise ? (condition d'acces patron) */
    public boolean ownsCompany(UUID player) {
        return eco.companies.companiesOf(player).join().stream().anyMatch(c -> c.owner().equals(player));
    }

    public String councilPasswordForWarning() { return settings.councilPassword(); }

    public int port() { return server == null ? -1 : server.getAddress().getPort(); }

    public void start() throws IOException {
        if (!settings.enabled()) { log.accept("Panneau web désactivé dans la configuration."); return; }
        try (InputStream in = WebPanel.class.getResourceAsStream("/web/index.html")) {
            if (in == null) throw new IOException("web/index.html introuvable dans le mod");
            page = in.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress(settings.bindAddress(), settings.port()), 50);
        httpPool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "CitizenEconomy-web"); t.setDaemon(true); return t; });
        server.setExecutor(httpPool);
        server.createContext("/", this::route);
        server.start();
        log.accept("Panneau web des entreprises : http://" + settings.bindAddress() + ":" + port());
    }

    public void stop() {
        if (server != null) { server.stop(0); server = null; }
        if (httpPool != null) httpPool.shutdownNow();
    }

    // ------------------------------------------------------------------ routage

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) { send(ex, 200, "text/html; charset=utf-8", page, null); return; }
            if (!method.equals("POST") || !path.startsWith("/api/")) { json(ex, 404, err("Introuvable.")); return; }
            if (!"1".equals(ex.getRequestHeaders().getFirst("X-CE"))) { json(ex, 403, err("Requête refusée.")); return; }
            Map<String, Object> body = readBody(ex);
            switch (path) {
                case "/api/login" -> login(ex, body);
                case "/api/logout" -> { String t = token(ex); if (t != null) sessions.remove(t); sendJson(ex, 200, Map.of("ok", true), "ce_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"); }
                case "/api/session" -> { Session s = session(ex); if (s == null) json(ex, 401, err("Non connecté.")); else json(ex, 200, who(s)); }
                case "/api/ui" -> action(ex, body);
                default -> json(ex, 404, err("Introuvable."));
            }
        } catch (BadRequest e) {
            json(ex, 400, err(e.getMessage()));
        } catch (Throwable t) {
            log.accept("Erreur panneau web : " + t);
            try { json(ex, 500, err("Erreur interne.")); } catch (IOException ignored) { }
        }
    }

    private static final class BadRequest extends RuntimeException { private static final long serialVersionUID = 1L; BadRequest(String m) { super(m); } }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) { raw = in.readNBytes(MAX_BODY + 1); }
        if (raw.length > MAX_BODY) throw new BadRequest("Requête trop grande.");
        if (raw.length == 0) return Map.of();
        try {
            Object o = Json.parse(new String(raw, StandardCharsets.UTF_8));
            if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (RuntimeException ignored) { }
        throw new BadRequest("Requête invalide.");
    }

    // ------------------------------------------------------------------ connexion

    private void login(HttpExchange ex, Map<String, Object> body) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        if (blocked(ip)) { json(ex, 429, err("Trop d'essais. Réessayez dans quelques minutes.")); return; }
        Actor actor = null;
        boolean council = false;
        Object pw = body.get("password");
        Object code = body.get("code");
        if (pw instanceof String p) {
            String expected = settings.councilPassword();
            if (expected != null && !expected.isEmpty()
                    && MessageDigest.isEqual(p.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                actor = Actor.council(); council = true;
            }
        } else if (code instanceof String c) {
            Optional<LoginCodes.Owner> o = codes.consume(c);
            if (o.isPresent() && ownsCompany(o.get().id())) actor = new Actor(o.get().id(), o.get().name(), false); // jamais admin sur le web
        }
        if (actor == null) {
            fail(ip);
            json(ex, 401, err(pw instanceof String ? "Mot de passe incorrect." : "Code invalide, expiré ou aucune entreprise à votre nom."));
            return;
        }
        fails.remove(ip);
        if (council) eco.players.touch(actor).join(); // l'identite du Conseil doit exister (auteur d'annonces, journaux...)
        if (sessions.size() >= MAX_SESSIONS) purgeSessions();
        if (sessions.size() >= MAX_SESSIONS) { json(ex, 503, err("Trop de sessions ouvertes.")); return; }
        byte[] tok = new byte[32];
        rnd.nextBytes(tok);
        String token = HexFormat.of().formatHex(tok);
        Session s = new Session(actor, council, new long[]{clock.getAsLong() + SESSION_MS});
        sessions.put(token, s);
        log.accept("Panneau web : connexion de " + actor.name() + (council ? " (Conseil royal)" : ""));
        sendJson(ex, 200, who(s), "ce_session=" + token + "; Max-Age=" + (SESSION_MS / 1000) + "; Path=/; HttpOnly; SameSite=Strict");
    }

    private Map<String, Object> who(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.actor().name()); m.put("council", s.council());
        return m;
    }

    private boolean blocked(String ip) {
        ArrayDeque<Long> q = fails.get(ip);
        if (q == null) return false;
        long now = clock.getAsLong();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > FAIL_WINDOW_MS) q.pollFirst();
            return q.size() >= MAX_FAILS;
        }
    }

    private void fail(String ip) {
        ArrayDeque<Long> q = fails.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(clock.getAsLong()); }
    }

    private void purgeSessions() {
        long now = clock.getAsLong();
        sessions.values().removeIf(s -> s.expiresAt()[0] < now);
    }

    private String token(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Cookie");
        if (h == null) return null;
        for (String part : h.split(";")) {
            String p = part.trim();
            if (p.startsWith("ce_session=")) return p.substring(11);
        }
        return null;
    }

    private Session session(HttpExchange ex) {
        String t = token(ex);
        if (t == null) return null;
        Session s = sessions.get(t);
        if (s == null) return null;
        long now = clock.getAsLong();
        if (s.expiresAt()[0] < now) { sessions.remove(t); return null; }
        s.expiresAt()[0] = now + SESSION_MS; // session glissante
        return s;
    }

    // ------------------------------------------------------------------ actions

    @SuppressWarnings("unchecked")
    private void action(HttpExchange ex, Map<String, Object> body) throws IOException {
        Session s = session(ex);
        if (s == null) { json(ex, 401, err("Session expirée : reconnectez-vous.")); return; }
        if (!(body.get("action") instanceof String action)) throw new BadRequest("Requête invalide.");
        Map<String, String> args;
        try { args = ViewCodec.argsFromJson(Json.write(body.getOrDefault("args", Map.of()))); }
        catch (IllegalArgumentException e) { throw new BadRequest("Paramètres invalides."); }
        if (!WebPolicy.actionAllowed(action, s.council())) { json(ex, 403, err("Action non disponible sur le panneau web.")); return; }
        String back = WebPolicy.normalizeView(args.get("_v"));
        String to = WebPolicy.normalizeView(args.get("to"));
        if (!WebPolicy.viewAllowed(back, s.council()) || (action.equals("open") && !WebPolicy.viewAllowed(to, s.council()))) { json(ex, 403, err("Écran non disponible sur le panneau web.")); return; }
        args.put("_v", back);
        if (action.equals("open")) args.put("to", to);
        CompletableFuture<View> f = CompletableFuture.supplyAsync(() -> ui.handle(s.actor(), action, args), worker);
        View v;
        try { v = f.get(20, TimeUnit.SECONDS); }
        catch (TimeoutException e) { json(ex, 504, err("Le serveur met trop de temps à répondre.")); return; }
        catch (InterruptedException | ExecutionException e) { json(ex, 500, err("Erreur interne.")); return; }
        send(ex, 200, "application/json; charset=utf-8", ViewCodec.toJson(WebPolicy.filter(v, s.council())).getBytes(StandardCharsets.UTF_8), null);
    }

    // ------------------------------------------------------------------ reponses

    private static Map<String, Object> err(String m) { return Map.of("error", m); }

    private void json(HttpExchange ex, int code, Map<String, Object> m) throws IOException { sendJson(ex, code, m, null); }

    private void sendJson(HttpExchange ex, int code, Map<String, Object> m, String cookie) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(m).getBytes(StandardCharsets.UTF_8), cookie);
    }

    private void send(HttpExchange ex, int code, String type, byte[] data, String cookie) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'");
        if (cookie != null) h.add("Set-Cookie", cookie);
        ex.sendResponseHeaders(code, data.length);
        try (var out = ex.getResponseBody()) { out.write(data); }
    }
}
