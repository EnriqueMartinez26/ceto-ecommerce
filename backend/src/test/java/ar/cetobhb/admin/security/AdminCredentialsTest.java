package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The administrator credential is validated by a factory with FIXED messages (AUTH-01, design AD-A4): a rejected value
 * must never reach an exception message or the console, because Boot's own failure report would print it.
 *
 * <p>No hash literal appears in this file on purpose ({@code NoCommittedHashTest} scans the sources):
 * hashes are generated at run time.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminCredentialsTest {

    private static final String PREFIX = "{bcrypt}";
    private static final String SENTINEL = "s3ntinel-Zk91";
    private static final String GOOD_USER = "owner";

    /** One real cost-10 hash for the whole class (BCrypt is slow on purpose). */
    private static final String RAW_HASH = new BCryptPasswordEncoder(10).encode("a-long-enough-test-password");

    private static String hashWithCost(String cost) {
        return PREFIX + RAW_HASH.replace("$10$", "$" + cost + "$");
    }

    static Stream<String> badUsernames() {
        return Stream.of(null, "", " ", "   ", "own er", "owner ", " owner", "own\ter", "own\ner", "owner\r", "own\u0000er",
                "own\u007Fer", "own​er", "own er", "own er", "x".repeat(65),
                "${ADMIN_USERNAME}", "own${er");
    }

    @ParameterizedTest(name = "username [{0}] is rejected")
    @MethodSource("badUsernames")
    void aMissingBlankOrUnsafeUsernameIsRejected(String username) {
        assertThatThrownBy(() -> AdminCredentials.parse(username, PREFIX + RAW_HASH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AdminCredentials.INVALID_USERNAME);
    }

    static Stream<String> badHashes() {
        return Stream.of(null, "", " ", PREFIX, "{noop}secret", "secret", "{sha256}" + "a".repeat(64),
                "{bcrypt" + RAW_HASH, RAW_HASH, "{BCRYPT}" + RAW_HASH, " " + PREFIX + RAW_HASH,
                PREFIX + RAW_HASH + " ", PREFIX + RAW_HASH + "\n", PREFIX + RAW_HASH + "\r\n", PREFIX + RAW_HASH + "\r", PREFIX + RAW_HASH + "x",
                PREFIX + RAW_HASH.substring(0, RAW_HASH.length() - 1),
                PREFIX + RAW_HASH.replace("$2a$", "$2x$"),
                PREFIX + "$2a$10$" + "!".repeat(53),
                PREFIX + "not-a-bcrypt-body",
                hashWithCost("09"), hashWithCost("15"), hashWithCost("9"), hashWithCost("05"), hashWithCost("31"),
                "x".repeat(10_000));
    }

    @ParameterizedTest(name = "hash #{index} is rejected")
    @MethodSource("badHashes")
    void aMissingWeakOrUnparsableHashIsRejected(String hash) {
        assertThatThrownBy(() -> AdminCredentials.parse(GOOD_USER, hash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AdminCredentials.INVALID_PASSWORD_HASH);
    }

    static Stream<Arguments> goodCosts() {
        return Stream.of(Arguments.of("10", 10), Arguments.of("11", 11), Arguments.of("12", 12),
                Arguments.of("13", 13), Arguments.of("14", 14));
    }

    @ParameterizedTest(name = "cost {0} is accepted")
    @MethodSource("goodCosts")
    void aBcryptHashWithCostTenToFourteenIsAcceptedAndTheCostIsParsed(String cost, int expected) {
        AdminCredentials credentials = AdminCredentials.parse(GOOD_USER, hashWithCost(cost));

        assertThat(credentials.cost()).isEqualTo(expected);
        assertThat(credentials.username()).isEqualTo(GOOD_USER);
        assertThat(credentials.bcryptHash()).as("the prefix is stripped").startsWith("$2")
                .isEqualTo(RAW_HASH.replace("$10$", "$" + cost + "$"));
    }

    @Test
    void theBcryptVersionLettersAAndYAndBAreAccepted() {
        for (String version : new String[] {"2a", "2b", "2y"}) {
            String hash = PREFIX + RAW_HASH.replace("$2a$", "$" + version + "$");
            assertThat(AdminCredentials.parse(GOOD_USER, hash).bcryptHash()).startsWith("$" + version + "$");
        }
    }

    @Test
    void aUsernameOfExactlySixtyFourCharactersAndOneCharacterIsAccepted() {
        assertThat(AdminCredentials.parse("x".repeat(64), PREFIX + RAW_HASH).username()).hasSize(64);
        assertThat(AdminCredentials.parse("x", PREFIX + RAW_HASH).username()).isEqualTo("x");
    }

    @Test
    void theFailureNeverContainsTheOffendingValueNorReachesTheConsole(CapturedOutput output) {
        for (String bad : new String[] {"{noop}" + SENTINEL, SENTINEL, "{sha256}" + SENTINEL,
            PREFIX + "$2a$09$" + SENTINEL}) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                    () -> AdminCredentials.parse(GOOD_USER, bad));
            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(failure.getMessage()).doesNotContain(SENTINEL).isEqualTo(AdminCredentials.INVALID_PASSWORD_HASH);
            assertThat(failure).hasNoCause();
        }
        for (String bad : new String[] {SENTINEL + " ", SENTINEL + "\u0000"}) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                    () -> AdminCredentials.parse(bad, PREFIX + RAW_HASH));
            assertThat(failure.getMessage()).doesNotContain(SENTINEL).isEqualTo(AdminCredentials.INVALID_USERNAME);
            assertThat(failure).hasNoCause();
        }
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    @Test
    void theToStringOfValidCredentialsShowsNeitherTheUsernameNorTheHash() {
        AdminCredentials credentials = AdminCredentials.parse("owner-" + SENTINEL, PREFIX + RAW_HASH);

        assertThat(credentials.toString()).doesNotContain(SENTINEL).doesNotContain(RAW_HASH).doesNotContain("$2");
    }

    @Test
    void theFixedMessagesNameThePropertyAndNeverTheValue() {
        assertThat(AdminCredentials.INVALID_USERNAME).contains("ceto.admin.username");
        assertThat(AdminCredentials.INVALID_PASSWORD_HASH).contains("ceto.admin.password-hash");
    }

    @Test
    void theFixedMessagesHintAtTheLikelyCauseWithoutEchoingAnyValue() {
        assertThat(AdminCredentials.INVALID_PASSWORD_HASH).contains("stray spaces", "line breaks", "$$")
                .contains("compose");
        assertThat(AdminCredentials.INVALID_USERNAME).contains("stray spaces", "line breaks");
        assertThat(AdminCredentials.INVALID_PASSWORD_HASH).doesNotContain("$2").doesNotContain("{bcrypt}$");
    }
}
