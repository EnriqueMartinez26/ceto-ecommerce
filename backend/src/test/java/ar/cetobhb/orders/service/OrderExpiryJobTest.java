package ar.cetobhb.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.cetobhb.orders.config.OrderProperties;
import org.assertj.core.api.InstanceOfAssertFactories;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * What one tick reports. The accumulated counts and the "log or stay silent" decision are only observable through
 * the tick's log line, so the tests read it with an in-memory appender (message text only, no PII is involved).
 */
class OrderExpiryJobTest {

    private static final int BATCH_SIZE = 3;

    private final OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);
    private final OrderExpiryJob job = new OrderExpiryJob(lifecycle,
            new OrderProperties(48, 10, 20, 3, new OrderProperties.Expiry(BATCH_SIZE, 45_000, 10)));
    private final Logger jobLogger = (Logger) LoggerFactory.getLogger(OrderExpiryJob.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        jobLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        jobLogger.detachAppender(appender);
        appender.stop();
    }

    private static ExpiryResult result(int expired, int failed) {
        return new ExpiryResult(LongStream.rangeClosed(1, expired).boxed().toList(),
                LongStream.rangeClosed(100, 99L + failed).boxed().toList());
    }

    private List<String> messages(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void anIdleTickLogsNothing() {
        when(lifecycle.expireDueOrders(BATCH_SIZE)).thenReturn(result(0, 0));

        job.run();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void reportsTheExpiredCountWhenNothingFailed() {
        when(lifecycle.expireDueOrders(BATCH_SIZE)).thenReturn(result(2, 0));

        job.run();

        assertThat(messages(Level.INFO)).singleElement(InstanceOfAssertFactories.STRING).matches(
                "Expiry tick: 2 unpaid orders expired and their reservations released, 0 failed \\(took \\d+ ms\\)");
        assertThat(messages(Level.WARN)).isEmpty();
    }

    @Test
    void reportsFailuresEvenWhenNothingExpired() {
        when(lifecycle.expireDueOrders(BATCH_SIZE)).thenReturn(result(0, 2));

        job.run();

        assertThat(messages(Level.WARN)).containsExactly("Expiry tick ended early: 2 orders failed in the last batch");
        assertThat(messages(Level.INFO)).singleElement(InstanceOfAssertFactories.STRING).matches(
                "Expiry tick: 0 unpaid orders expired and their reservations released, 2 failed \\(took \\d+ ms\\)");
    }

    @Test
    void sumsTheCountsOfEveryBatchOfTheTick() {
        // Two full batches, then a last batch that fails two orders and ends the tick.
        when(lifecycle.expireDueOrders(BATCH_SIZE)).thenReturn(result(3, 0), result(3, 0), result(1, 2));

        job.run();

        assertThat(messages(Level.INFO)).singleElement(InstanceOfAssertFactories.STRING).matches(
                "Expiry tick: 7 unpaid orders expired and their reservations released, 2 failed \\(took \\d+ ms\\)");
    }
}
