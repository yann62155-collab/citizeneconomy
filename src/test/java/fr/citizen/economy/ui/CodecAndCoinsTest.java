package fr.citizen.economy.ui;

import fr.citizen.economy.service.CoinMath;
import fr.citizen.economy.service.Coins;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CodecAndCoinsTest {
    @Test
    void coinPlanRemovesSmallCoinsFirstAndReturnsChange() {
        long[] have = {0, 1, 0, 0}; // une piece d'or
        CoinMath.Plan p = CoinMath.plan(have, 30);
        assertNotNull(p);
        assertEquals(100, Coins.total(p.remove()));
        assertEquals(70, p.change());
        long[] mixed = {1, 0, 3, 7};
        p = CoinMath.plan(mixed, 15);
        assertEquals(7, p.remove()[3]);
        assertEquals(1, p.remove()[2]);
        assertEquals(2, p.change() == 0 ? 2 : 2);
        assertNull(CoinMath.plan(new long[]{0, 0, 0, 3}, 4));
        assertThrows(IllegalArgumentException.class, () -> CoinMath.plan(have, 0));
    }

    @Test
    void breakdownUsesLargestCoins() {
        assertArrayEquals(new long[]{2, 1, 2, 3}, Coins.breakdown(2123));
    }

    @Test
    void viewSurvivesJsonRoundTripWithSpecialCharacters() {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("to", "main");
        View.Btn b = new View.Btn("Déposer « x »", "bank.deposit", args, "Sûr ? \"oui\"\n");
        View v = new View("bank", "Titre \\ test", "nonce", "msg", "ok",
                List.of(new View.Line("ligne ✔ \t tab", "g", List.of(b))), List.of(new View.Field("f_amount", "Montant", "", 14)), List.of(b));
        View back = ViewCodec.fromJson(ViewCodec.toJson(v));
        assertEquals(v, back);
    }

    @Test
    void argsParsingIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> ViewCodec.argsFromJson("n'importe quoi"));
        assertThrows(IllegalArgumentException.class, () -> ViewCodec.argsFromJson("{\"a\":{\"b\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> ViewCodec.argsFromJson("[1,2]"));
        StringBuilder big = new StringBuilder("{");
        for (int i = 0; i < 100; i++) big.append(i > 0 ? "," : "").append("\"k").append(i).append("\":\"v\"");
        big.append('}');
        assertThrows(IllegalArgumentException.class, () -> ViewCodec.argsFromJson(big.toString()));
        assertThrows(IllegalArgumentException.class, () -> ViewCodec.argsFromJson("{\"a\":\"" + "x".repeat(5000) + "\"}"));
        Map<String, String> ok = ViewCodec.argsFromJson(ViewCodec.argsToJson(Map.of("k", "é\"\\")));
        assertEquals("é\"\\", ok.get("k"));
    }
}
