package ar.cetobhb.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractServerIT;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Request-target variants of {@code POST /api/checkout/orders} on a real server (REQ-ABU-02). The limiter is an MVC
 * interceptor on the handler mapping, so the invariant is: a variant either reaches the handler THROUGH the interceptor
 * (and spends the same client bucket as the canonical path) or is not mapped at all (404, no body read, nothing to
 * limit). No variant may get a 400 (body parsed) after the bucket is empty. The expectations below pin what Tomcat 11
 * and Spring MVC 7 actually do with each form.
 */
class CheckoutPathLimitHttpIT extends AbstractServerIT {

    private static final String CANONICAL = "/api/checkout/orders";
    private static final int BURST = 10;
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger();

    private int attemptAt(String path, String client) throws Exception {
        return postToPath(path, client, "key-" + System.nanoTime(), "{").status();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            /api/checkout/orders;x=1                | 429
            /api/checkout/orders;x=1;y=2            | 429
            /api/checkout/orders;                   | 429
            /api//checkout/orders                   | 404
            /api/checkout/./orders                  | 404
            /api/checkout/%6Frders                  | 429
            /api/checkout/orders/                   | 404
            /API/checkout/orders                    | 404
            /api/checkout/ORDERS                    | 404
            /api/checkout/orders.json               | 404
            """)
    void aVariantEitherSpendsTheCanonicalBucketOrIsNotMapped(String variant, int expected) throws Exception {
        String client = "192.0.2." + (1 + NEXT_CLIENT.getAndIncrement());
        for (int i = 0; i < BURST; i++) {
            assertThat(attemptAt(CANONICAL, client)).as("canonical attempt %d", i + 1).isEqualTo(400);
        }

        assertThat(attemptAt(variant.trim(), client)).as("variant %s with an empty bucket", variant.trim())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> counted={1}")
    @CsvSource(delimiter = '|', textBlock = """
            /api/checkout/orders;x=1                | true
            /api/checkout/orders/                   | false
            /API/checkout/orders                    | false
            """)
    void aMappedVariantSpendsTheBucketAndAnUnmappedOneDoesNot(String variant, boolean counted) throws Exception {
        String client = "192.0.2." + (100 + NEXT_CLIENT.getAndIncrement());
        for (int i = 0; i < BURST; i++) {
            attemptAt(variant.trim(), client);
        }

        assertThat(attemptAt(CANONICAL, client)).as("canonical path after %d hits on %s", BURST, variant.trim())
                .isEqualTo(counted ? 429 : 400);
    }
}
