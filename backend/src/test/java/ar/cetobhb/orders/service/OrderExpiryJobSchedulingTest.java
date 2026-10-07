package ar.cetobhb.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.cetobhb.common.config.SchedulingConfig;
import ar.cetobhb.orders.config.OrderProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

/**
 * Wiring of the expiry job (REQ-LIF-06, AD-12, AD-13): with {@code ceto.scheduling.enabled=true} the job is
 * registered as a fixed-delay task whose pause comes from configuration and each tick delegates to the lifecycle
 * service with the configured batch size; with {@code false} (the test profile) no scheduling infrastructure runs.
 */
class OrderExpiryJobSchedulingTest {

    private static final OrderLifecycleService SERVICE = mock(OrderLifecycleService.class);

    @Configuration(proxyBeanMethods = false)
    static class JobConfig {

        @Bean
        OrderLifecycleService lifecycleService() {
            return SERVICE;
        }

        @Bean
        OrderProperties orderProperties() {
            return new OrderProperties(48, 10, 20, new OrderProperties.Expiry(7, 45_000));
        }

        @Bean
        OrderExpiryJob orderExpiryJob(OrderLifecycleService lifecycle, OrderProperties properties) {
            return new OrderExpiryJob(lifecycle, properties);
        }
    }

    private static ExpiryResult result(int expired) {
        return new ExpiryResult(java.util.stream.LongStream.rangeClosed(1, expired).boxed().toList(), List.of());
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, JobConfig.class)
            // A long pause: only the first tick (immediate) runs during a test.
            .withPropertyValues("ceto.orders.expiry.fixed-delay-ms=45000");

    @Test
    void whenSchedulingIsEnabledTheJobIsARegisteredFixedDelayTaskReadingTheConfiguredPause() {
        org.mockito.Mockito.reset(SERVICE);
        when(SERVICE.expireDueOrders(anyInt())).thenReturn(result(0));

        runner.withPropertyValues("ceto.scheduling.enabled=true").run(context -> {
            ScheduledAnnotationBeanPostProcessor processor =
                    context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            Set<ScheduledTask> tasks = processor.getScheduledTasks();

            assertThat(tasks).hasSize(1);
            assertThat(tasks.iterator().next().getTask()).isInstanceOfSatisfying(FixedDelayTask.class,
                    task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMillis(45_000)));
            // The first tick delegates to the service with the configured batch size.
            verify(SERVICE, timeout(5_000)).expireDueOrders(7);
        });
    }

    @Test
    void whenSchedulingIsDisabledNothingIsRegisteredAndTheServiceIsNeverCalled() {
        org.mockito.Mockito.reset(SERVICE);

        runner.withPropertyValues("ceto.scheduling.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(OrderExpiryJob.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(SERVICE);
        });
    }

    @Test
    void theTickDelegatesToTheServiceAndCarriesNoTransactionOfItsOwn() throws NoSuchMethodException {
        org.mockito.Mockito.reset(SERVICE);
        when(SERVICE.expireDueOrders(7)).thenReturn(result(3));

        new OrderExpiryJob(SERVICE, new OrderProperties(48, 10, 20, new OrderProperties.Expiry(7, 45_000))).run();

        verify(SERVICE).expireDueOrders(7);
        Method run = OrderExpiryJob.class.getMethod("run");
        assertThat(run.getAnnotation(Scheduled.class).fixedDelayString())
                .isEqualTo("${ceto.orders.expiry.fixed-delay-ms}");
        assertThat(run.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }
}
