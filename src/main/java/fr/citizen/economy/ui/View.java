package fr.citizen.economy.ui;

import java.util.List;
import java.util.Map;

/**
 * Description d'un ecran, construite et validee cote SERVEUR puis simplement affichee par le client
 * (interface "pilotee par le serveur" : le client ne decide rien, il renvoie seulement l'action cliquee).
 * style des lignes : h = titre, n = normal, g = or, r = rouge, m = discret.
 */
public record View(String id, String title, String nonce, String message, String kind,
                   List<Line> lines, List<Field> fields, List<Btn> buttons) {

    public record Btn(String label, String action, Map<String, String> args, String confirm) { }

    public record Line(String text, String style, List<Btn> btns) { }

    public record Field(String id, String label, String value, int max) { }
}
