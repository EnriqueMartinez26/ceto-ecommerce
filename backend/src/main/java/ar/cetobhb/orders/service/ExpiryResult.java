package ar.cetobhb.orders.service;

import java.util.List;

/**
 * Outcome of one {@link OrderLifecycleService#expireDueOrders} run.
 *
 * @param expiredOrderIds orders moved to CANCELADO (EXPIRED) by this run
 * @param failedOrderIds  orders that could not be expired and stay PAGO_PENDIENTE (logged by id, no PII)
 */
public record ExpiryResult(List<Long> expiredOrderIds, List<Long> failedOrderIds) {

    public ExpiryResult {
        expiredOrderIds = List.copyOf(expiredOrderIds);
        failedOrderIds = List.copyOf(failedOrderIds);
    }

    public int expired() {
        return expiredOrderIds.size();
    }

    public int failed() {
        return failedOrderIds.size();
    }

    /** Orders examined by the run: a value equal to the batch size means more due orders may be waiting. */
    public int processed() {
        return expired() + failed();
    }
}
