package ar.cetobhb.admin.security;

import ar.cetobhb.admin.config.AdminProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The account store and the authentication manager of the administration area (design AD-A4, AD-A5).
 *
 * <p>Declaring a {@link UserDetailsService} bean makes Boot's {@code UserDetailsServiceAutoConfiguration} back off, so
 * no "user" account with a random password printed to the log ever exists, in any profile
 * ({@code NoGeneratedPasswordTest}, {@code NoGeneratedUserIT}). The account is the one in {@code ceto.admin.*}, checked
 * by {@link AdminCredentials#parse} before anything else is built: an invalid credential stops the startup here, with a
 * fixed message.
 *
 * <p>The manager is explicit (no {@code AuthenticationManagerBuilder}, no {@code DelegatingPasswordEncoder}) and
 * {@code DaoAuthenticationProvider} keeps {@code hideUserNotFoundExceptions} on, so an unknown user and a wrong
 * password are the same {@code BadCredentialsException}.
 */
@Configuration(proxyBeanMethods = false)
class AdminAuthenticationConfig {

    @Bean
    AdminCredentials adminCredentials(AdminProperties properties) {
        return AdminCredentials.parse(properties.username(), properties.passwordHash());
    }

    /**
     * The BCrypt work, at the cost of the stored hash. It is a separate bean (and the only {@code PasswordEncoder}
     * built from the credential) so that a test can wrap it and count how many hashes were really computed.
     */
    @Bean
    PasswordEncoder adminBcryptEncoder(AdminCredentials credentials) {
        return new BCryptPasswordEncoder(credentials.cost());
    }

    @Bean
    AdminPasswordEncoder adminPasswordEncoder(@Qualifier("adminBcryptEncoder") PasswordEncoder bcrypt) {
        return new AdminPasswordEncoder(bcrypt);
    }

    @Bean
    UserDetailsService adminUserDetailsService(AdminCredentials credentials) {
        return new AdminUserDetailsService(credentials);
    }

    @Bean
    AuthenticationManager adminAuthenticationManager(UserDetailsService adminUserDetailsService,
            AdminPasswordEncoder adminPasswordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(adminUserDetailsService);
        provider.setPasswordEncoder(adminPasswordEncoder);
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }
}
