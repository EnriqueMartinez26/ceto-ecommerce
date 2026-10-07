package ar.cetobhb.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The string form of the checkout records is what reaches logs: it must say something, and never carry PII. */
class PiiRedactionTest {

    private static final String NAME = "Maria Gonzalez";
    private static final String PHONE = "+5493515551234";
    private static final String ADDRESS = "Calle Falsa 123";
    private static final UUID KEY = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void buyerToStringIsRedactedButNotEmpty() {
        String text = new Buyer(NAME, PHONE, ADDRESS, ShippingZone.CORDOBA_CAPITAL).toString();

        assertThat(text).isEqualTo("Buyer[redacted, zone=CORDOBA_CAPITAL]")
                .doesNotContain(NAME, PHONE, ADDRESS, "351");
    }

    @Test
    void newOrderToStringIdentifiesTheOrderWithoutPii() {
        NewOrder order = new NewOrder("CETO-000007", KEY, "f".repeat(64), NAME, PHONE, ADDRESS,
                ShippingZone.CORDOBA_CAPITAL, Instant.parse("2026-10-07T12:00:00Z"), List.of());

        assertThat(order.toString())
                .isEqualTo("NewOrder[number=CETO-000007, key=" + KEY + ", zone=CORDOBA_CAPITAL]")
                .doesNotContain(NAME, PHONE, ADDRESS);
    }

    @Test
    void normalizedRequestToStringIdentifiesTheRequestWithoutPii() {
        NormalizedOrderRequest request = new NormalizedOrderRequest(KEY, NAME, PHONE, ADDRESS,
                ShippingZone.CORDOBA_CAPITAL, List.of());

        assertThat(request.toString())
                .isEqualTo("NormalizedOrderRequest[key=" + KEY + ", zone=CORDOBA_CAPITAL, lines=[]]")
                .doesNotContain(NAME, PHONE, ADDRESS);
    }
}
