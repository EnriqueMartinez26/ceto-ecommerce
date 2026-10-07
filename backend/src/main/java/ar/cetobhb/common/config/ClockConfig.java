package ar.cetobhb.common.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place allowed to read the system clock; everything else receives this bean. The clock ticks in whole
 * microseconds, the precision of Postgres {@code timestamptz}, so an instant stamped in Java reads back unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
