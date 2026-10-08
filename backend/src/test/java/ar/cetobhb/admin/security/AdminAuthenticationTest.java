package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ar.cetobhb.admin.config.AdminProperties;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The authentication manager of the administration area (REQ-AUTH-02, REQ-AUTH-03): an unknown user, an over-long user
 * name and a wrong password are indistinguishable, the right credentials authenticate, and a login attempt costs the
 * same BCrypt work whether the user exists or not.
 */
class AdminAuthenticationTest {

    private static final String USER = "owner";
    private static final String PASSWORD = "a-long-enough-test-password";

    /** Cost 10 is the lowest the credential accepts; computed once for the class. */
    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(10).encode(PASSWORD);

    private PasswordEncoder bcrypt;
    private AuthenticationManager manager;
    private AdminUserDetailsService users;

    @BeforeEach
    void wire() {
        AdminAuthenticationConfig config = new AdminAuthenticationConfig();
        AdminCredentials credentials = config.adminCredentials(new AdminProperties(USER, HASH, 480, 720,
                new AdminProperties.LoginRateLimit(5, 90, 10_000, 20, 40), 200, 100));
        bcrypt = spy(config.adminBcryptEncoder(credentials).encoder());
        users = (AdminUserDetailsService) config.adminUserDetailsService(credentials);
        manager = config.adminAuthenticationManager(users, config.adminPasswordEncoder(new AdminBcrypt(bcrypt)));
    }

    private Authentication attempt(String username, String password) {
        return manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    }

    @Test
    void theCorrectCredentialsAuthenticateEveryTime() {
        // The principal is erased after a login; a second login proves the account store returns a fresh object.
        for (int i = 0; i < 2; i++) {
            Authentication result = attempt(USER, PASSWORD);
            assertThat(result.isAuthenticated()).isTrue();
            assertThat(result.getName()).isEqualTo(USER);
            assertThat(result.getAuthorities()).extracting("authority").contains("ROLE_ADMIN").doesNotContain("ROLE_USER");
        }
    }

    @Test
    void anUnknownUserAWrongPasswordAndAnOverLongUserAreTheSameBadCredentials() {
        Throwable wrongPassword = catchThrowable(() -> attempt(USER, "not-the-password"));
        Throwable unknownUser = catchThrowable(() -> attempt("somebody-else", PASSWORD));
        Throwable longUser = catchThrowable(() -> attempt("u".repeat(129), PASSWORD));
        Throwable emptyUser = catchThrowable(() -> attempt("", PASSWORD));

        for (Throwable failure : new Throwable[] {wrongPassword, unknownUser, longUser, emptyUser}) {
            assertThat(failure).isExactlyInstanceOf(BadCredentialsException.class);
            assertThat(failure.getMessage()).isEqualTo(wrongPassword.getMessage());
        }
    }

    @Test
    void theUsernameIsCaseSensitiveAndExact() {
        assertThatThrownBy(() -> attempt("OWNER", PASSWORD)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> attempt("owne", PASSWORD)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> attempt("owner ", PASSWORD)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void anUnknownUserStillSpendsOneBcryptComparisonSoTheResponseTimeDoesNotRevealTheAccount() {
        org.mockito.Mockito.clearInvocations(bcrypt);

        assertThatThrownBy(() -> attempt("somebody-else", PASSWORD)).isInstanceOf(BadCredentialsException.class);

        verify(bcrypt, times(1)).matches(any(CharSequence.class), any(String.class));
    }

    @Test
    void aWrongPasswordSpendsExactlyOneBcryptComparisonToo() {
        org.mockito.Mockito.clearInvocations(bcrypt);

        assertThatThrownBy(() -> attempt(USER, "not-the-password")).isInstanceOf(BadCredentialsException.class);

        verify(bcrypt, times(1)).matches(any(CharSequence.class), any(String.class));
    }

    @Test
    void anOverLongPasswordIsRefusedWithoutAnyBcryptComparison() {
        org.mockito.Mockito.clearInvocations(bcrypt);

        assertThatThrownBy(() -> attempt(USER, "p".repeat(100_000))).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> attempt("somebody-else", "p".repeat(100_000)))
                .isInstanceOf(BadCredentialsException.class);

        verify(bcrypt, never()).matches(any(CharSequence.class), any(String.class));
    }

    @Test
    void theAccountStoreKnowsOnlyTheConfiguredUser() {
        UserDetails details = users.loadUserByUsername(USER);

        assertThat(details.getUsername()).isEqualTo(USER);
        assertThat(details.getPassword()).startsWith("$2");
        assertThatThrownBy(() -> users.loadUserByUsername("other")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> users.loadUserByUsername(null)).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> users.loadUserByUsername("x".repeat(129)))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void theUsernameIsComparedInConstantTime() {
        // Timing cannot be asserted reliably; the structural guarantee is that the comparison is MessageDigest.isEqual.
        JavaClass userDetailsService = new ClassFileImporter().importClass(AdminUserDetailsService.class);

        assertThat(userDetailsService.getMethodCallsFromSelf()).anyMatch(call ->
                call.getTargetOwner().isEquivalentTo(java.security.MessageDigest.class)
                        && call.getName().equals("isEqual"));
    }
}
