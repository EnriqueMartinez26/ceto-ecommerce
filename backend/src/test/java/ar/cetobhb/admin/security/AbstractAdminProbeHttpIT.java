package ar.cetobhb.admin.security;

import adminprobe.AdminProbeTestConfig;
import ar.cetobhb.support.AbstractHttpIT;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * MockMvc base for the tests that need the test-only admin probe handler and the chain marker
 * ({@link AdminProbeTestConfig}). It is a context of its own, so the shared contexts (and {@code EndpointInventoryIT})
 * stay exact. The pool is small: these tests barely touch the database.
 */
@Import(AdminProbeTestConfig.class)
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=4")
public abstract class AbstractAdminProbeHttpIT extends AbstractHttpIT {
}
