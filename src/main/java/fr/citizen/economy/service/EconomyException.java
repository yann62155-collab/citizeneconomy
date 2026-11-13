package fr.citizen.economy.service;

import java.util.concurrent.CompletionException;

/** Erreur metier previsible (permission, validation...). Le code sert a la traduction cote interface. */
public final class EconomyException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    public EconomyException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }

    public static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        return t;
    }
}
