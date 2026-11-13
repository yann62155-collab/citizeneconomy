package fr.citizen.economy.domain;

import java.util.UUID;

/** txUuid est la cle d'idempotence : rejouer la meme requete ne deplace jamais l'argent deux fois. */
public record TxRequest(UUID txUuid, TxType type, long amount, AccountRef source, AccountRef target,
                        String description, UUID initiator, Long invoiceId) {
    public TxRequest(UUID txUuid, TxType type, long amount, AccountRef source, AccountRef target, String description, UUID initiator) {
        this(txUuid, type, amount, source, target, description, initiator, null);
    }
}
