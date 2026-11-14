package fr.citizen.economy.web;

import fr.citizen.economy.ui.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce que le panneau web a le droit de faire : la gestion des entreprises pour les patrons ; en plus, toute l'administration du royaume
 * pour le Conseil royal. Jamais la banque personnelle ni les depots/retraits en pieces (ils exigent l'inventaire d'un joueur en jeu).
 */
public final class WebPolicy {
    private WebPolicy() { }

    public static final String HOME = "companies";

    static final Set<String> VIEWS = Set.of("companies", "company", "employees", "perms", "salaryhist", "cbank", "cboard", "cinvoices", "ctax");

    static final Set<String> ACTIONS = Set.of(
            "open",
            "emp.hire", "emp.fire", "emp.salary", "emp.grade", "emp.perm", "emp.promote", "emp.demote",
            "cbank.pay",
            "ann.publish", "ann.edit", "ann.delete",
            "inv.create", "inv.pay", "inv.refuse", "inv.cancel",
            "tax.pay");

    /** Ecrans d'administration du royaume, reserves au Conseil royal. */
    static boolean adminView(String v) { return v.equals("admin") || v.equals("treasury") || v.startsWith("adm_"); }

    public static boolean actionAllowed(String action, boolean council) {
        return ACTIONS.contains(action) || (council && action.startsWith("adm."));
    }

    public static boolean viewAllowed(String view, boolean council) {
        return VIEWS.contains(view) || (council && adminView(view));
    }

    /** L'ecran de retour par defaut ("main") devient la liste des entreprises. */
    public static String normalizeView(String view) {
        return view == null || view.isBlank() || view.equals("main") ? HOME : view;
    }

    /** Retire de l'ecran tout bouton que le panneau web n'a pas le droit d'utiliser. */
    public static View filter(View v, boolean council) {
        List<View.Line> lines = new ArrayList<>();
        for (View.Line l : v.lines()) lines.add(new View.Line(l.text(), l.style(), keep(l.btns(), council)));
        return new View(v.id(), v.title(), v.nonce(), v.message(), v.kind(), lines, v.fields(), keep(v.buttons(), council));
    }

    private static List<View.Btn> keep(List<View.Btn> in, boolean council) {
        List<View.Btn> out = new ArrayList<>();
        for (View.Btn b : in) {
            if (!actionAllowed(b.action(), council)) continue;
            if (b.action().equals("open")) {
                String to = normalizeView(b.args().get("to"));
                if (!viewAllowed(to, council)) continue;
                if (!to.equals(b.args().get("to"))) {
                    Map<String, String> a = new java.util.LinkedHashMap<>(b.args());
                    a.put("to", to);
                    b = new View.Btn(b.label(), b.action(), a, b.confirm());
                }
            }
            out.add(b);
        }
        return out;
    }
}
