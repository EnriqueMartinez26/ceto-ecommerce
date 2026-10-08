package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.support.AbstractHttpIT;
import ar.cetobhb.support.AdminTestCredentials;
import ar.cetobhb.support.CountingPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The real application context authenticates the generated test credential through the explicit manager, and the test
 * wiring (X2.6) counts the BCrypt work of the account: an attempt refused by the 72-byte guard costs no hash.
 */
class AdminCredentialsWiringIT extends AbstractHttpIT {

    @Autowired
    @Qualifier("adminAuthenticationManager")
    private AuthenticationManager manager;

    @Autowired
    @Qualifier("adminBcryptEncoder")
    private AdminBcrypt bcrypt;

    @Autowired
    private AdminProperties properties;

    private void login(String username, String password) {
        manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    }

    @Test
    void theGeneratedTestCredentialAuthenticatesAndAWrongPasswordDoesNot() {
        login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThatThrownBy(() -> login(AdminTestCredentials.username(), AdminTestCredentials.password() + "x"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void theBcryptWorkOfTheAccountIsCountedInTheTestContexts() {
        assertThat(bcrypt.encoder()).isInstanceOf(CountingPasswordEncoder.class);
        int before = CountingPasswordEncoder.matches();

        login(AdminTestCredentials.username(), AdminTestCredentials.password());

        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before + 1);
    }

    @Test
    void anOverLongPasswordCostsNoBcryptWorkEvenForAnUnknownUser() {
        int before = CountingPasswordEncoder.matches();

        assertThatThrownBy(() -> login(AdminTestCredentials.username(), "p".repeat(100_000)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> login("nobody", "p".repeat(100_000))).isInstanceOf(BadCredentialsException.class);

        assertThat(CountingPasswordEncoder.matches()).isEqualTo(before);
    }

    @Test
    void thePropertiesObjectDoesNotPrintTheCredential() {
        assertThat(properties.toString()).doesNotContain(AdminTestCredentials.username())
                .doesNotContain(AdminTestCredentials.passwordHash()).doesNotContain("$2a$");
    }
}
