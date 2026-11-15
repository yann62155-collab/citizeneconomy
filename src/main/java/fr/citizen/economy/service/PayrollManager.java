package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;
import fr.citizen.economy.persistence.Database;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Paiement des salaires. Chaque (employe, periode) a un identifiant de transaction DETERMINISTE :
 * relancer la paie (crash, double declenchement, rattrapage) ne paie jamais deux fois la meme periode.
 * Le declenchement periodique est branche sur le tick serveur (phase 9) ; periodKey = epoch / duree de periode.
 */
public final class PayrollManager {
    public record Report(long companyId, int paid, int alreadyPaid, int unpaid, long totalPaid, boolean skipped) { }

    private final Database db;

    public PayrollManager(Database db) { this.db = db; }

    public static UUID salaryTxId(long employeeId, long periodKey) {
        return UUID.nameUUIDFromBytes(("citizeneconomy:salary:" + employeeId + ":" + periodKey).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID royalSalaryTxId(UUID player, long periodKey) {
        return UUID.nameUUIDFromBytes(("citizeneconomy:royalsalary:" + player + ":" + periodKey).getBytes(StandardCharsets.UTF_8));
    }

    /** Paie des employes de la couronne depuis la tresorerie royale (meme garantie : jamais deux fois la meme periode). @return {payes, deja payes, impayes} */
    public CompletableFuture<int[]> runRoyal(long periodKey) {
        return db.write(c -> {
            int paid = 0, already = 0, unpaid = 0;
            long now = System.currentTimeMillis();
            for (String[] row : Sql.query(c, "SELECT player_uuid, salary FROM royal_staff WHERE salary > 0 ORDER BY hired_at", rs -> new String[]{rs.getString(1), rs.getString(2)})) {
                UUID player = UUID.fromString(row[0]);
                long salary = Long.parseLong(row[1]);
                try {
                    TxResult r = TransactionManager.apply(c, new TxRequest(royalSalaryTxId(player, periodKey), TxType.ROYAL_EXPENSE, salary,
                            AccountRef.treasury(), AccountRef.personal(player), "Salaire de la couronne", null));
                    if (r.replayed()) already++;
                    else { paid++; Records.notify(c, player, "SALARY_PAID", "Salaire de la couronne reçu : " + salary + " PO.", now); }
                } catch (EconomyException e) {
                    if (!e.code().equals("INSUFFICIENT_FUNDS")) throw e;
                    unpaid++;
                    Records.notify(c, player, "SALARY_UNPAID", "Salaire de la couronne impayé : la trésorerie royale est insuffisante.", now);
                }
            }
            return new int[]{paid, already, unpaid};
        });
    }

    public CompletableFuture<Report> runCompany(long companyId, long periodKey) {
        return db.write(c -> {
            Company co = Records.company(c, companyId);
            if (co.status() != CompanyStatus.ACTIVE) return new Report(companyId, 0, 0, 0, 0, true);
            int paid = 0, already = 0, unpaid = 0;
            long total = 0;
            List<String> unpaidNames = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Member m : Sql.query(c, Records.MEMBER_SELECT + "WHERE e.company_id = ? AND e.fired_at IS NULL AND e.salary > 0 ORDER BY e.hired_at, e.id",
                    Records.MEMBER, companyId)) {
                try {
                    TxResult r = TransactionManager.apply(c, new TxRequest(salaryTxId(m.employeeId(), periodKey), TxType.SALARY, m.salary(),
                            AccountRef.business(companyId), AccountRef.personal(m.player()), "Salaire", null));
                    if (r.replayed()) already++;
                    else {
                        paid++;
                        total += m.salary();
                        Records.notify(c, m.player(), "SALARY_PAID", "Salaire reçu de « " + co.name() + " » : " + m.salary() + " PO.", now);
                    }
                } catch (EconomyException e) {
                    if (!e.code().equals("INSUFFICIENT_FUNDS")) throw e;
                    unpaid++;
                    unpaidNames.add(m.name());
                }
            }
            if (unpaid > 0) {
                String msg = "Fonds insuffisants : " + unpaid + " salaire(s) impayé(s) chez « " + co.name() + " » (" + String.join(", ", unpaidNames) + ").";
                Records.notify(c, co.owner(), "SALARY_UNPAID", msg, now);
                if (co.manager() != null) Records.notify(c, co.manager(), "SALARY_UNPAID", msg, now);
            }
            return new Report(companyId, paid, already, unpaid, total, false);
        });
    }

    public CompletableFuture<List<Report>> runAll(long periodKey) {
        return db.read(c -> Sql.query(c, "SELECT id FROM companies WHERE status = 'ACTIVE' ORDER BY id", rs -> rs.getLong(1)))
                .thenCompose(ids -> {
                    CompletableFuture<List<Report>> acc = CompletableFuture.completedFuture(new ArrayList<>());
                    for (long id : ids) {
                        acc = acc.thenCompose(list -> runCompany(id, periodKey).thenApply(r -> { list.add(r); return list; }));
                    }
                    return acc;
                }).thenCompose(list -> runRoyal(periodKey).thenApply(x -> list));
    }
}
