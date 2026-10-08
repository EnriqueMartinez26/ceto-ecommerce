package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * The login field cap (REQ-AUTH-02): a username or password over {@value LoginFieldCapAuthenticationManager#MAX_FIELD_CHARS}
 * characters is refused BEFORE the real manager is asked, so neither the account store nor BCrypt ever sees it. The
 * refusal is the same {@code BadCredentialsException} as a wrong password, with a fixed message.
 */
class LoginFieldCapAuthenticationManagerTest {

    private final AtomicInteger delegated = new AtomicInteger();
    private final Authentication accepted = UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of());
    private final AuthenticationManager delegate = authentication -> {
        delegated.incrementAndGet();
        return accepted;
    };
    private final AuthenticationManager capped = new LoginFieldCapAuthenticationManager(delegate);

    private static Authentication attempt(Object principal, Object credentials) {
        return UsernamePasswordAuthenticationToken.unauthenticated(principal, credentials);
    }

    @Test
    void aCredentialAtTheCapIsPassedToTheRealManager() {
        Authentication result = capped.authenticate(attempt("u".repeat(128), "p".repeat(128)));

        assertThat(result).isSameAs(accepted);
        assertThat(delegated).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {129, 1000, 100_000})
    void aUsernameOverTheCapIsRefusedWithoutAskingTheRealManager(int length) {
        assertThatThrownBy(() -> capped.authenticate(attempt("u".repeat(length), "short")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(delegated).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {129, 1000, 100_000})
    void aPasswordOverTheCapIsRefusedWithoutAskingTheRealManager(int length) {
        assertThatThrownBy(() -> capped.authenticate(attempt("admin", "p".repeat(length))))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(delegated).hasValue(0);
    }

    @Test
    void theRefusalIsTheSameFixedMessageWhicheverFieldIsTooLongAndNamesNoValue() {
        String sentinelUser = "sentinel-user-" + "u".repeat(200);
        String sentinelPassword = "sentinel-pass-" + "p".repeat(200);

        Throwable byUser = catchOf(() -> capped.authenticate(attempt(sentinelUser, "x")));
        Throwable byPassword = catchOf(() -> capped.authenticate(attempt("admin", sentinelPassword)));

        assertThat(byUser).isInstanceOf(BadCredentialsException.class);
        assertThat(byPassword).isInstanceOf(BadCredentialsException.class);
        assertThat(byUser.getMessage()).isEqualTo(byPassword.getMessage())
                .doesNotContain("sentinel").doesNotContain("admin");
        assertThat(byUser.getCause()).isNull();
    }

    @Test
    void theCapCountsCharactersNotBytes() {
        // 128 two-byte characters are 256 bytes and still inside the cap; the 72-byte BCrypt guard is another layer.
        capped.authenticate(attempt("admin", "é".repeat(128)));

        assertThat(delegated).hasValue(1);
    }

    @Test
    void aMissingUsernameOrPasswordIsLeftToTheRealManager() {
        capped.authenticate(attempt("", ""));

        assertThat(delegated).hasValue(1);
    }

    @Test
    void aTokenThatIsNotAUsernamePasswordAttemptIsLeftToTheRealManager() {
        Authentication other = new org.springframework.security.authentication.TestingAuthenticationToken("a", "b");

        capped.authenticate(other);

        assertThat(delegated).hasValue(1);
    }

    private static Throwable catchOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
