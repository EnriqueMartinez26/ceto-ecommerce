package ar.cetobhb.admin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The account store of the administration area. Until the administrator credential is wired in (slice X2) it knows
 * nobody. Declaring a {@link UserDetailsService} bean makes Boot's {@code UserDetailsServiceAutoConfiguration} back
 * off, so no "user" account with a random password printed to the log ever exists, in any profile. Slice X2 replaces
 * this bean with the one backed by {@code ceto.admin.*}; the back-off keeps working the same way.
 */
@Configuration(proxyBeanMethods = false)
class AdminAuthenticationConfig {

    @Bean
    UserDetailsService denyAllUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("No administrator account is configured");
        };
    }
}
