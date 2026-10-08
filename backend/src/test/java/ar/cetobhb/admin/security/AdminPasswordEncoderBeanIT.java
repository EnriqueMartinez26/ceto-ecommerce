package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractHttpIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Type-based injection of a {@link PasswordEncoder} must reach the guarded {@link AdminPasswordEncoder} and nothing
 * else: a raw BCrypt bean next to it would let any later component (a second provider, a user store) bypass the
 * 72-byte guard just by asking for a {@code PasswordEncoder}.
 */
class AdminPasswordEncoderBeanIT extends AbstractHttpIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PasswordEncoder injectedByType;

    @Test
    void theContextHasExactlyOnePasswordEncoderAndItIsTheGuardedOne() {
        Map<String, PasswordEncoder> encoders = context.getBeansOfType(PasswordEncoder.class);

        assertThat(encoders).hasSize(1);
        assertThat(encoders.values().iterator().next()).isInstanceOf(AdminPasswordEncoder.class);
    }

    @Test
    void injectionByTypeResolvesToTheGuardedEncoder() {
        assertThat(injectedByType).isInstanceOf(AdminPasswordEncoder.class);
    }
}
