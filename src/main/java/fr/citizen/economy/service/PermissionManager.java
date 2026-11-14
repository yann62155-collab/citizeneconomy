package fr.citizen.economy.service;

import fr.citizen.economy.domain.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Toutes les verifications de droits passent ici, cote serveur, DANS la transaction de l'operation
 * (aucune fenetre entre le controle et l'action).
 */
public final class PermissionManager {
    private PermissionManager() { }

    public static final int ALL = EnumSet.allOf(Permission.class).stream().mapToInt(Permission::bit).reduce(0, (a, b) -> a | b);

    public static int defaultsFor(CompanyRole role) {
        return switch (role) {
            case OWNER, MANAGER -> ALL;
            case EMPLOYEE -> Permission.PUBLISH_ANNOUNCEMENTS.bit();
        };
    }

    /** Retire d'un masque tout ce qu'un role n'a pas le droit de detenir. */
    public static int sanitize(CompanyRole role, int mask) {
        if (role == CompanyRole.OWNER) return ALL;
        int out = mask & ALL;
        if (role == CompanyRole.EMPLOYEE) {
            for (Permission p : Permission.values()) if (p.managerOnly) out &= ~p.bit();
        }
        return out;
    }

    public static boolean has(Member m, Permission p) {
        return m.role() == CompanyRole.OWNER || (m.permissions() & p.bit()) != 0;
    }

    /** Contexte d'autorisation resolu pour une operation sur une entreprise. */
    public record Authority(Actor actor, Member member, Company company) {
        public boolean admin() { return actor.admin(); }

        /** Peut-on gerer (licencier, noter, payer...) la cible ? Admin > proprietaire > gerant > employe. */
        public boolean canManage(Member target) {
            if (admin() || actor.isCouncil()) return true;
            return switch (member.role()) {
                case OWNER -> target.role() != CompanyRole.OWNER || target.player().equals(actor.id());
                case MANAGER -> target.role() == CompanyRole.EMPLOYEE;
                case EMPLOYEE -> false;
            };
        }

        public boolean isOwnerOrAdmin() { return admin() || member.role() == CompanyRole.OWNER; }
    }

    /**
     * @param needed   permission requise (null = simple appartenance)
     * @param mutating l'operation modifie l'entreprise : refusee hors statut ACTIVE (sauf admin)
     */
    static Authority authorize(Connection c, Actor actor, long companyId, Permission needed, boolean mutating)
            throws SQLException {
        Company co = Records.company(c, companyId);
        // Conseil royal : patron de toutes les entreprises (tous les droits, y compris signer au nom de l'entreprise).
        if (actor.isCouncil()) {
            if (mutating && co.status() != CompanyStatus.ACTIVE)
                throw new EconomyException("COMPANY_NOT_ACTIVE", "L'entreprise n'est pas active.");
            return new Authority(actor, councilMember(companyId), co);
        }
        // Un admin qui est aussi membre de l'entreprise agit en tant que membre (facturer, publier...), sans perdre ses droits d'admin.
        if (actor.admin()) return new Authority(actor, Records.activeMember(c, companyId, actor.id()).orElse(null), co);
        Optional<Member> m = Records.activeMember(c, companyId, actor.id());
        if (m.isEmpty()) throw new EconomyException("FORBIDDEN", "Vous ne faites pas partie de cette entreprise.");
        if (mutating && co.status() != CompanyStatus.ACTIVE)
            throw new EconomyException("COMPANY_NOT_ACTIVE", "L'entreprise n'est pas active.");
        if (needed != null && !has(m.get(), needed))
            throw new EconomyException("FORBIDDEN", "Permission insuffisante.");
        return new Authority(actor, m.get(), co);
    }

    /** Membre fictif "patron" representant le Conseil royal dans une entreprise. */
    public static Member councilMember(long companyId) {
        return new Member(0, companyId, Actor.COUNCIL_ID, "Conseil royal", CompanyRole.OWNER, "Conseil royal", 0, ALL, 0);
    }

    static void requireAdmin(Actor actor) {
        if (!actor.admin()) throw new EconomyException("FORBIDDEN", "Réservé à l'administration.");
    }
}
