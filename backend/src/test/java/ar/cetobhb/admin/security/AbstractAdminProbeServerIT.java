package ar.cetobhb.admin.security;

import adminprobe.AdminProbeTestConfig;
import ar.cetobhb.support.AbstractServerIT;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Real-server (random port) base for the tests that need the test-only admin probe handler and the chain marker
 * ({@link AdminProbeTestConfig}). Its context is separate from the shared ones, so {@code EndpointInventoryIT} stays
 * exact.
 */
@Import(AdminProbeTestConfig.class)
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=4")
public abstract class AbstractAdminProbeServerIT extends AbstractServerIT {
}
