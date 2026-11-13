package fr.citizen.economy.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Periodes fiscales. Hebdomadaire (10080 min) : du dimanche 00:00 au dimanche suivant 00:00 (fuseau configurable,
 * gere l'heure d'ete). Une periode se cloture donc tous les dimanches a minuit. Autres durees : multiples d'epoch.
 */
public final class TaxPeriods {
    public static final int WEEKLY = 10_080;

    private TaxPeriods() { }

    public static long floorBoundary(long ts, int minutes, ZoneId zone) {
        if (minutes == WEEKLY) {
            LocalDate d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
            return d.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        long ms = minutes * 60_000L;
        return Math.floorDiv(ts, ms) * ms;
    }

    /** Debut de la periode suivante (strictement apres 'boundary'). */
    public static long nextBoundary(long boundary, int minutes, ZoneId zone) {
        if (minutes == WEEKLY) {
            LocalDate d = Instant.ofEpochMilli(boundary).atZone(zone).toLocalDate();
            return d.with(TemporalAdjusters.next(DayOfWeek.SUNDAY)).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        return boundary + minutes * 60_000L;
    }

    /** base * rateBp / 10000 arrondi a l'inferieur, sans depassement de long. */
    public static long percentOfBp(long base, int rateBp) {
        if (base < 0 || rateBp < 0) throw new IllegalArgumentException();
        return Math.addExact(Math.multiplyExact(base / 10_000, (long) rateBp), (base % 10_000) * rateBp / 10_000);
    }
}
