package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractHttpIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * The session policy is wired into the REAL application context, not only into the test fixtures: the cookie guard
 * runs at startup, the registry that enforces one session exists, and the publisher that feeds it container session
 * events is registered. The behaviour of each piece is pinned elsewhere ({@code AdminSessionGuardTest},
 * {@code SingleSessionServerIT}); this class fails if a piece disappears from the application.
 */
class AdminSessionPolicyWiringIT extends AbstractHttpIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void theCookieGuardIsPartOfTheApplication() {
        assertThat(context.getBeansOfType(AdminSessionGuard.class)).hasSize(1);
    }

    @Test
    void theSecurityLogLevelGuardIsPartOfTheApplication() {
        assertThat(context.getBeansOfType(SecurityLogLevelGuard.class)).hasSize(1);
    }

    @Test
    void thereIsExactlyOneSessionRegistryAndOneSessionEventPublisher() {
        assertThat(context.getBeansOfType(SessionRegistry.class)).hasSize(1);
        Map<String, HttpSessionEventPublisher> publishers = context.getBeansOfType(HttpSessionEventPublisher.class);
        assertThat(publishers).hasSize(1);
    }

    @Test
    void theEffectiveCookieSettingsAreTheShippedOnes() {
        var environment = context.getEnvironment();
        assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("strict");
        assertThat(environment.getProperty("server.servlet.session.tracking-modes")).isEqualTo("cookie");
    }
}
