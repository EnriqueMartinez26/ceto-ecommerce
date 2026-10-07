package ar.cetobhb.orders.service;

import ar.cetobhb.orders.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger of the reservation expiry (REQ-LIF-06, AD-12). It owns no transaction and no logic: each tick
 * runs ONE batch through {@link OrderLifecycleService#expireDueOrders}, which is the transactional unit.
 *
 * <p>The pause between ticks comes from {@code ceto.orders.expiry.fixed-delay-ms}, the batch size from
 * {@code ceto.orders.expiry.batch-size}. Scheduling only runs when {@code SchedulingConfig} is active
 * ({@code ceto.scheduling.enabled}, off in the test profile), so tests never see a background tick.
 *
 * <p>A failing batch rolls back entirely and the exception is logged by the scheduler's error handler; the next
 * tick retries the same orders. It is deliberately not swallowed here: a broken stock invariant must be loud.
 */
@Component
public class OrderExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final OrderLifecycleService lifecycle;
    private final int batchSize;

    public OrderExpiryJob(OrderLifecycleService lifecycle, OrderProperties properties) {
        this.lifecycle = lifecycle;
        this.batchSize = properties.expiry().batchSize();
    }

    @Scheduled(fixedDelayString = "${ceto.orders.expiry.fixed-delay-ms}")
    public void run() {
        int expired = lifecycle.expireDueOrders(batchSize).expired();
        if (expired > 0) {
            LOG.info("Expired {} unpaid orders and released their reservations", expired);
        }
    }
}
