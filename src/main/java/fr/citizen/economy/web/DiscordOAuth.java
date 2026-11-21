package fr.citizen.economy.web;

import fr.citizen.economy.ui.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Connexion « Se connecter avec Discord » (OAuth2, portee « identify » seulement : on ne lit que l'identifiant du compte).
 * Le navigateur est envoye chez Discord, qui le ramene avec un code a usage unique ; le serveur echange ce code
 * contre l'identifiant Discord. Le secret de l'application ne quitte jamais le serveur.
 */
public final class DiscordOAuth {
    /** Acces reseau, remplacable dans les tests. */
    public interface Http {
        String postForm(String url, Map<String, String> form) throws IOException;
        String get(String url, String bearer) throws IOException;
    }

    static final String AUTHORIZE = "https://discord.com/oauth2/authorize";
    static final String TOKEN = "https://discord.com/api/v10/oauth2/token";
    static final String ME = "https://discord.com/api/v10/users/@me";

    private final WebSettings settings;
    private final Http http;

    public DiscordOAuth(WebSettings settings, Http http) { this.settings = settings; this.http = http; }

    public DiscordOAuth(WebSettings settings) { this(settings, jdkHttp()); }

    public boolean enabled() {
        return settings.discordEnabled() && !settings.discordClientId().isBlank() && !settings.discordClientSecret().isBlank() && !settings.discordRedirectUri().isBlank();
    }

    public String authorizeUrl(String state) {
        return AUTHORIZE + "?client_id=" + enc(settings.discordClientId()) + "&response_type=code&scope=identify&prompt=none"
                + "&redirect_uri=" + enc(settings.discordRedirectUri()) + "&state=" + enc(state);
    }

    /** Echange le code contre l'identifiant Discord du membre. */
    public String userIdFor(String code) throws IOException {
        if (code == null || code.isBlank() || code.length() > 200) throw new IOException("code invalide");
        String body = http.postForm(TOKEN, Map.of("client_id", settings.discordClientId(), "client_secret", settings.discordClientSecret(),
                "grant_type", "authorization_code", "code", code, "redirect_uri", settings.discordRedirectUri()));
        String token = str(parse(body), "access_token");
        if (token == null) throw new IOException("Discord n'a pas donne de jeton");
        String id = str(parse(http.get(ME, token)), "id");
        if (id == null || !id.matches("\\d{15,25}")) throw new IOException("identifiant Discord invalide");
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) throws IOException {
        try {
            Object o = Json.parse(body);
            if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (RuntimeException ignored) { }
        throw new IOException("reponse Discord illisible");
    }

    private static String str(Map<String, Object> m, String k) { return m.get(k) instanceof String s ? s : null; }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    public static Http jdkHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        return new Http() {
            public String postForm(String url, Map<String, String> form) throws IOException {
                StringJoiner j = new StringJoiner("&");
                form.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
                return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "CitizenEconomy (portail web)")
                        .POST(HttpRequest.BodyPublishers.ofString(j.toString())).build());
            }

            public String get(String url, String bearer) throws IOException {
                return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + bearer).header("User-Agent", "CitizenEconomy (portail web)").GET().build());
            }

            private String send(HttpRequest r) throws IOException {
                try {
                    HttpResponse<String> res = client.send(r, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 400) throw new IOException("Discord HTTP " + res.statusCode());
                    return res.body();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrompu");
                }
            }
        };
    }
}
