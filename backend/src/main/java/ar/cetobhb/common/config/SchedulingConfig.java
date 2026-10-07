package ar.cetobhb.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs run unless {@code ceto.scheduling.enabled=false} (the test profile). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ceto.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
