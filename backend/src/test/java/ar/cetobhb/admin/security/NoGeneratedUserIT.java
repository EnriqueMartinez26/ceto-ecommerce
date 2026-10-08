package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.AbstractHttpIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Boot's default user (a user named "user" with a random password printed to the log) must not exist in the real
 * application context: the only account is the configured one, and Boot never invents a second (review fix batch 5,
 * item 5; X2.5 asserts the real {@link AdminUserDetailsService}).
 */
class NoGeneratedUserIT extends AbstractHttpIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void noInMemoryUserDetailsManagerExists() {
        assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
    }

    @Test
    void theOnlyUserDetailsServiceKnowsOnlyTheConfiguredAdministrator() {
        assertThat(context.getBeansOfType(UserDetailsService.class)).hasSize(1);
        UserDetailsService service = context.getBean(UserDetailsService.class);

        assertThatThrownBy(() -> service.loadUserByUsername("user")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> service.loadUserByUsername("admin")).isInstanceOf(UsernameNotFoundException.class);
        assertThat(service).isInstanceOf(AdminUserDetailsService.class);
        assertThat(service.loadUserByUsername(ar.cetobhb.support.AdminTestCredentials.username()).getUsername())
                .isEqualTo(ar.cetobhb.support.AdminTestCredentials.username());
    }
}
