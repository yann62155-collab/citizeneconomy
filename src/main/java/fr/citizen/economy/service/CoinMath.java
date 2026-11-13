package fr.citizen.economy.service;

/** Calcul pur (testable) du retrait de pieces de l'inventaire pour un depot, avec rendu de monnaie. */
public final class CoinMath {
    /** remove = pieces a retirer (index = ordinal de Coins.Denomination), change = valeur a rendre en pieces. */
    public record Plan(long[] remove, long change) { }

    private CoinMath() { }

    /** @return null si les pieces possedees ne couvrent pas le montant. */
    public static Plan plan(long[] have, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("montant <= 0");
        long total = Coins.total(have);
        if (total < amount) return null;
        Coins.Denomination[] d = Coins.Denomination.values(); // PLATINE, OR, ARGENT, BRONZE
        long[] remove = new long[d.length];
        long remaining = amount;
        for (int i = d.length - 1; i >= 0 && remaining > 0; i--) { // petites pieces d'abord
            long v = d[i].value;
            long need = (remaining + v - 1) / v;
            long n = Math.min(have[i], need);
            remove[i] = n;
            remaining -= n * v;
        }
        long removed = Coins.total(remove);
        return new Plan(remove, removed - amount);
    }
}
