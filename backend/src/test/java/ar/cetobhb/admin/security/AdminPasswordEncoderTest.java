package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt reads at most 72 bytes of a password. The stored password is provisioned within that limit, so anything longer
 * can never be the right one, and the encoder must say no without hashing (REQ-AUTH-02, design AD-A5): that removes the
 * truncation (a 73-byte password matching its own first 72 bytes) and the unbounded input.
 */
class AdminPasswordEncoderTest {

    /** Cost 4 is the minimum BCrypt allows; the behaviour under test does not depend on the work factor. */
    private final PasswordEncoder inner = spy(new BCryptPasswordEncoder(4));
    private final AdminPasswordEncoder encoder = new AdminPasswordEncoder(inner);

    @Test
    void aNullPasswordNeverMatchesAndTheInnerEncoderIsNotCalled() {
        String hash = inner.encode("whatever");
        org.mockito.Mockito.clearInvocations(inner);

        assertThat(encoder.matches(null, hash)).isFalse();

        verifyNoInteractions(inner);
    }

    @Test
    void aPasswordOf73AsciiBytesNeverMatchesAndTheInnerEncoderIsNotCalled() {
        String stored = inner.encode("a".repeat(72));
        org.mockito.Mockito.clearInvocations(inner);

        assertThat(encoder.matches("a".repeat(73), stored))
                .as("BCrypt alone would match: it ignores everything after byte 72").isFalse();

        verify(inner, never()).matches(any(CharSequence.class), any(String.class));
    }

    @Test
    void theLimitIsInBytesNotCharacters() {
        // 37 two-byte characters: 37 chars, 74 bytes.
        String raw = "é".repeat(37);
        String stored = inner.encode("x");
        org.mockito.Mockito.clearInvocations(inner);

        assertThat(raw.length()).isLessThan(72);
        assertThat(encoder.matches(raw, stored)).isFalse();

        verify(inner, never()).matches(any(CharSequence.class), any(String.class));
    }

    @Test
    void aHugePasswordIsRejectedWithoutHashingIt() {
        String stored = inner.encode("x");
        org.mockito.Mockito.clearInvocations(inner);

        assertThat(encoder.matches("p".repeat(100_000), stored)).isFalse();

        verify(inner, never()).matches(any(CharSequence.class), any(String.class));
    }

    @Test
    void aPasswordOfExactly72BytesIsDelegatedAndCanMatch() {
        String password = "b".repeat(72);
        String stored = inner.encode(password);

        assertThat(encoder.matches(password, stored)).isTrue();

        verify(inner).matches(password, stored);
    }

    @Test
    void aMultiByteBoundaryOf72BytesIsDelegated() {
        String password = "é".repeat(36);
        String stored = inner.encode(password);

        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(encoder.matches(password, stored)).isTrue();
    }

    @Test
    void aWrongPasswordDoesNotMatchAndTheCorrectOneDoes() {
        String stored = inner.encode("correct horse battery");

        assertThat(encoder.matches("correct horse battery", stored)).isTrue();
        assertThat(encoder.matches("correct horse batterz", stored)).isFalse();
        assertThat(encoder.matches("", stored)).isFalse();
    }

    @Test
    void encodeDelegatesToTheInnerEncoder() {
        String encoded = encoder.encode("some password");

        verify(inner).encode("some password");
        assertThat(inner.matches("some password", encoded)).isTrue();
    }

    @Test
    void neverAsksForAnUpgradeOfTheStoredHash() {
        assertThat(encoder.upgradeEncoding(inner.encode("x"))).isFalse();
    }
}
