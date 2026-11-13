package fr.citizen.economy.domain;

import java.util.UUID;

/** Soldes apres operation (-1 pour un cote CASH). replayed = la transaction existait deja, rien n'a ete re-execute. */
public record TxResult(long id, UUID txUuid, long sourceBalanceAfter, long targetBalanceAfter, boolean replayed) { }
