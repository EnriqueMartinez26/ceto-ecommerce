package ar.cetobhb.support;

import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.ShippingZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Buyers with a phone of their own. Tests that place many orders must not share one phone: the pending-orders cap
 * (REQ-ABU-01) is per phone and its advisory lock would also serialize the very concurrency a stock or lock-order
 * test wants to create.
 */
public final class BuyerFixtures {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private BuyerFixtures() {
    }

    /** A valid Argentine mobile number never handed out before in this JVM. */
    public static String freshPhone() {
        return "+5493515" + String.format("%06d", 500_000 + NEXT.getAndIncrement());
    }

    /** A buyer with a fresh phone. */
    public static Buyer freshBuyer() {
        return new Buyer("Maria Perez", freshPhone(), "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL);
    }
}
