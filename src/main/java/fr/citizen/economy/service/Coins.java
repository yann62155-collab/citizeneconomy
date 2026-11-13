package fr.citizen.economy.service;

/** Denominations des pieces physiques (valeurs validees : 1 / 10 / 100 / 1000 PO). */
public final class Coins {
    public enum Denomination {
        PLATINE(1000), OR(100), ARGENT(10), BRONZE(1);
        public final long value;
        Denomination(long value) { this.value = value; }
    }

    private Coins() { }

    /** Decompose en plus grosses pieces possibles. Index = ordinal de Denomination (platine d'abord). */
    public static long[] breakdown(long amount) {
        if (amount < 0) throw new IllegalArgumentException("montant negatif");
        long[] out = new long[Denomination.values().length];
        long rest = amount;
        for (Denomination d : Denomination.values()) {
            out[d.ordinal()] = rest / d.value;
            rest %= d.value;
        }
        return out;
    }

    /** Valeur totale d'un lot de pieces ; leve ArithmeticException en cas de depassement. */
    public static long total(long[] counts) {
        long sum = 0;
        for (Denomination d : Denomination.values()) {
            long n = counts[d.ordinal()];
            if (n < 0) throw new IllegalArgumentException("quantite negative");
            sum = Math.addExact(sum, Math.multiplyExact(n, d.value));
        }
        return sum;
    }
}
