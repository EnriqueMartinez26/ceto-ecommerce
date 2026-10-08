package ar.cetobhb.orders.service;

import ar.cetobhb.orders.config.OrderProperties;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * for the next tick, so one tick cannot run unbounded. A batch with failed orders (the service fell back and some
 * orders still failed) ends the tick right away with one WARN: those orders are retried by every iteration, and
 * the next tick tries again. The service itself stops its per-order fallback at the first lock timeout, so a tick
 * behind a held lock costs about two lock waits, not one per due order. A tick that expired or failed something logs
 * how long it took.
 *
 * <p>Trade-off, deliberate: because the fallback stops at the first lock timeout, a long-held lock on the stock of
 * the oldest-due order delays the expiry of the other due orders until it frees. For a holder that is idle inside
 * its transaction the delay is bounded by {@code idle_in_transaction_session_timeout}.
 *
 * <p>Failures: a batch that fails is rolled back by the service and retried order by order; the orders that still
 * fail are logged by the service at ERROR (id, exception class, SQLState) and stay PAGO_PENDIENTE, the rest
 * advance. An exception that
 * escapes the service is logged by the scheduler's error handler and does not cancel the schedule: the next tick
 * runs after the fixed delay.
 */
@Component
public class OrderExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final OrderLifecycleService lifecycle;
    private final int batchSize;
    private final int maxBatchesPerTick;

    private final LongSupplier nanoClock;

    @Autowired
    public OrderExpiryJob(OrderLifecycleService lifecycle, OrderProperties properties) {
        this(lifecycle, properties, System::nanoTime);
    }

    /** Test seam: the monotonic clock that times the tick. */
    OrderExpiryJob(OrderLifecycleService lifecycle, OrderProperties properties, LongSupplier nanoClock) {
        this.lifecycle = lifecycle;
        this.batchSize = properties.expiry().batchSize();
        this.maxBatchesPerTick = properties.expiry().maxBatchesPerTick();
        this.nanoClock = nanoClock;
    }

    @Scheduled(fixedDelayString = "${ceto.orders.expiry.fixed-delay-ms}")
    public void run() {
        long startedNanos = nanoClock.getAsLong();
        int expired = 0;
        int failed = 0;
        for (int batch = 0; batch < maxBatchesPerTick; batch++) {
            ExpiryResult result = lifecycle.expireDueOrders(batchSize);
            expired += result.expired();
            failed += result.failed();
            if (result.failed() > 0) {
                // The batch fell back and some orders failed: they are retried by every iteration, so draining on
                // would repeat their ERROR logs and the fallback within one tick. The next tick tries again.
                LOG.warn("Expiry tick ended early: {} orders failed in the last batch", result.failed());
                break;
            }
            if (result.expired() == 0 || result.processed() < batchSize) {
                break;
            }
        }
        if (expired > 0 || failed > 0) {
            // Duration only (no order data): a tick stuck behind a lock shows up here as a multiple of lock_timeout.
            LOG.info("Expiry tick: {} unpaid orders expired and their reservations released, {} failed (took {} ms)",
                    expired, failed, TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - startedNanos));
        }
    }
}
