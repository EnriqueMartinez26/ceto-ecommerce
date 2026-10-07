package ar.cetobhb.orders.service;

import ar.cetobhb.orders.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger of the reservation expiry (REQ-LIF-06, AD-12). It owns no transaction and no logic: each tick
 * runs batches through {@link OrderLifecycleService#expireDueOrders}, which owns the transactions (one per batch,
 * and one per order when a batch has to fall back, D-20261007-12).
 *
 * <p>The pause between ticks comes from {@code ceto.orders.expiry.fixed-delay-ms}, the batch size from
 * {@code ceto.orders.expiry.batch-size}. Scheduling only runs when {@code SchedulingConfig} is active
 * ({@code ceto.scheduling.enabled}, off in the test profile), so tests never see a background tick.
 *
 * <p>Backlog: a tick keeps running batches back to back while a batch comes back full (as many orders examined as
 * the batch size) and expired at least one order, at most {@code ceto.orders.expiry.max-batches-per-tick} times
 * (1 to 100, default 10). A tick therefore drains up to batch size times that cap, and a backlog beyond it waits
 * for the next tick, so one tick cannot run unbounded. A batch that expired nothing (every order of it failed) ends
 * the tick instead of spinning.
 *
 * <p>Failures: a batch that fails is rolled back by the service and retried order by order; the orders that still
 * fail are logged by the service at ERROR (id only) and stay PAGO_PENDIENTE, the rest advance. An exception that
 * escapes the service is logged by the scheduler's error handler and does not cancel the schedule: the next tick
 * runs after the fixed delay.
 */
@Component
public class OrderExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final OrderLifecycleService lifecycle;
    private final int batchSize;
    private final int maxBatchesPerTick;

    public OrderExpiryJob(OrderLifecycleService lifecycle, OrderProperties properties) {
        this.lifecycle = lifecycle;
        this.batchSize = properties.expiry().batchSize();
        this.maxBatchesPerTick = properties.expiry().maxBatchesPerTick();
    }

    @Scheduled(fixedDelayString = "${ceto.orders.expiry.fixed-delay-ms}")
    public void run() {
        int expired = 0;
        int failed = 0;
        for (int batch = 0; batch < maxBatchesPerTick; batch++) {
            ExpiryResult result = lifecycle.expireDueOrders(batchSize);
            expired += result.expired();
            failed += result.failed();
            if (result.expired() == 0 || result.processed() < batchSize) {
                break;
            }
        }
        if (expired > 0 || failed > 0) {
            LOG.info("Expiry tick: {} unpaid orders expired and their reservations released, {} failed", expired,
                    failed);
        }
    }
}
