package ar.cetobhb.orders;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.orders.service.OrderExpiryJob;
import ar.cetobhb.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** The real test context: the job bean exists, but nothing schedules it (AD-13, REQ-LIF-06). */
class OrderExpiryJobWiringIT extends AbstractPostgresIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void theJobBeanExistsButNoScheduledTaskIsRegisteredInTests() {
        assertThat(context.getBeanNamesForType(OrderExpiryJob.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}
