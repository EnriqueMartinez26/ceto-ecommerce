package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The hash provisioning command (REQ-AUTH-01, AUTH-14, X5.4). It prints ONLY the {@code {bcrypt}} value for
 * {@code ADMIN_PASSWORD_HASH}; it refuses a password the login could never accept (under 14 characters, over 72 UTF-8
 * bytes), never takes the password as an argument, and never writes it anywhere. Prompts and errors go to the error
 * stream so a redirect of the output captures the hash and nothing else.
 */
class AdminPasswordHashCommandTest {

    private static final String SENTINEL_PASSWORD = "S3ntinel-Pass-Qw83!";

    /** A scripted password source; it remembers what it handed out so the test can see it was wiped. */
    private static final class Script implements AdminPasswordHashCommand.PasswordSource {
        private final boolean interactive;
        private final List<String> answers;
        final List<char[]> handedOut = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();

        Script(boolean interactive, String... answers) {
            this.interactive = interactive;
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public boolean interactive() {
            return interactive;
        }

        @Override
        public char[] read(String prompt) {
            prompts.add(prompt);
            if (answers.isEmpty()) {
                return null;
            }
            char[] chars = answers.remove(0).toCharArray();
            handedOut.add(chars);
            return chars;
        }
    }

    private record Result(int code, String out, String err) {
    }

    private static Result run(String[] args, AdminPasswordHashCommand.PasswordSource source) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = AdminPasswordHashCommand.run(args, source, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Result runWith(String password) {
        return run(new String[0], new Script(false, password));
    }

    @Test
    void aValidPasswordPrintsOnlyTheBcryptValueAtCostTwelveAndExitsZero() {
        Result result = runWith(SENTINEL_PASSWORD);

        assertThat(result.code()).isZero();
        assertThat(result.out()).startsWith("{bcrypt}$2a$12$").endsWith("\n");
        assertThat(result.out().strip().lines()).hasSize(1);
        assertThat(result.err()).doesNotContain(SENTINEL_PASSWORD);
    }

    @Test
    void theValueIsAcceptedByTheCredentialValidationWithCostTwelveAndMatchesThePassword() {
        String value = runWith(SENTINEL_PASSWORD).out().strip();

        AdminCredentials credentials = AdminCredentials.parse("owner", value);

        assertThat(credentials.cost()).isEqualTo(12);
        assertThat(new BCryptPasswordEncoder(12).matches(SENTINEL_PASSWORD, value.substring("{bcrypt}".length())))
                .isTrue();
        assertThat(new BCryptPasswordEncoder(12).matches("another password!!", value.substring("{bcrypt}".length())))
                .isFalse();
    }

    @Test
    void twoRunsWithTheSamePasswordGiveDifferentHashes() {
        assertThat(runWith(SENTINEL_PASSWORD).out()).isNotEqualTo(runWith(SENTINEL_PASSWORD).out());
    }

    @Test
    void thePasswordIsHashedExactlyAsTypedSpacesIncluded() {
        String spaced = "  has spaces inside and around  ";

        String value = runWith(spaced).out().strip();

        assertThat(new BCryptPasswordEncoder(12).matches(spaced, value.substring("{bcrypt}".length()))).isTrue();
        assertThat(new BCryptPasswordEncoder(12).matches(spaced.strip(), value.substring("{bcrypt}".length())))
                .isFalse();
    }

    @ParameterizedTest(name = "{0} characters")
    @ValueSource(ints = {0, 1, 13})
    void aPasswordUnderFourteenCharactersIsRefusedWithNoOutput(int length) {
        String password = ("S3ntinel-Pass-Qw83!").substring(0, Math.min(length, 19));

        Result result = runWith(password);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("at least 14 characters");
        if (!password.isEmpty()) {
            assertThat(result.err()).doesNotContain(password);
        }
    }

    @Test
    void fourteenCharactersIsEnough() {
        assertThat(runWith("abcdefghijklmn").code()).isZero();
    }

    @Test
    void seventyTwoBytesIsTheLargestPasswordAndSeventyThreeIsRefused() {
        String atTheLimit = "p".repeat(72);
        String overTheLimit = "p".repeat(73);

        assertThat(runWith(atTheLimit).code()).isZero();
        Result refused = runWith(overTheLimit);
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.out()).isEmpty();
        assertThat(refused.err()).contains("at most 72 bytes").doesNotContain(overTheLimit);
    }

    @Test
    void theLimitIsInBytesNotCharacters() {
        // 36 two-byte characters are exactly 72 bytes; 37 are 74.
        assertThat(runWith("é".repeat(36)).code()).isZero();
        Result refused = runWith("é".repeat(37));
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.err()).contains("at most 72 bytes");
        // Fourteen characters of three bytes each are 42 bytes: long enough and short enough.
        assertThat(runWith("€".repeat(14)).code()).isZero();
    }

    @Test
    void anyArgumentIsRefusedBeforeTheSourceIsReadSoThePasswordCanNeverComeFromTheCommandLine() {
        Script source = new Script(false, SENTINEL_PASSWORD);

        Result result = run(new String[] {SENTINEL_PASSWORD}, source);

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("takes no arguments").doesNotContain(SENTINEL_PASSWORD);
        assertThat(source.prompts).as("the password source was never asked").isEmpty();
    }

    @Test
    void anEmptyOrMissingInputIsRefused() {
        Result eof = run(new String[0], new Script(false));

        assertThat(eof.code()).isEqualTo(1);
        assertThat(eof.out()).isEmpty();
    }

    @Test
    void aTerminalAsksTwiceAndHashesOnlyWhenTheEntriesMatch() {
        Script source = new Script(true, SENTINEL_PASSWORD, SENTINEL_PASSWORD);

        Result result = run(new String[0], source);

        assertThat(result.code()).isZero();
        assertThat(source.prompts).hasSize(2);
        assertThat(result.out()).startsWith("{bcrypt}$2a$12$");
        assertThat(result.err()).doesNotContain(SENTINEL_PASSWORD);
    }

    @Test
    void aTerminalWithTwoDifferentEntriesIsRefusedWithNoOutput() {
        Result result = run(new String[0], new Script(true, SENTINEL_PASSWORD, SENTINEL_PASSWORD + "x"));

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("differ").doesNotContain(SENTINEL_PASSWORD);
    }

    @Test
    void aPipedInputIsReadOnceWithoutAConfirmation() {
        Script source = new Script(false, SENTINEL_PASSWORD);

        run(new String[0], source);

        assertThat(source.prompts).hasSize(1);
    }

    @Test
    void everyCharacterArrayHandedOutIsWipedWhateverTheOutcome() {
        Script ok = new Script(true, SENTINEL_PASSWORD, SENTINEL_PASSWORD);
        Script mismatch = new Script(true, SENTINEL_PASSWORD, SENTINEL_PASSWORD + "x");
        Script tooShort = new Script(false, "short");

        run(new String[0], ok);
        run(new String[0], mismatch);
        run(new String[0], tooShort);

        for (Script script : List.of(ok, mismatch, tooShort)) {
            assertThat(script.handedOut).isNotEmpty();
            for (char[] chars : script.handedOut) {
                for (char c : chars) {
                    assertThat((int) c).as("the password buffer is wiped").isZero();
                }
            }
        }
    }

    @Test
    void thePromptsNameTheActionButNeverTheValue() {
        Script source = new Script(true, SENTINEL_PASSWORD, SENTINEL_PASSWORD);

        run(new String[0], source);

        assertThat(source.prompts).allSatisfy(prompt -> assertThat(prompt).doesNotContain(SENTINEL_PASSWORD)
                .containsIgnoringCase("password"));
    }

    // ---- the standard input source ----

    private static char[] readLine(String input) throws IOException {
        AdminPasswordHashCommand.PasswordSource source = AdminPasswordHashCommand.stdin(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        return source.read("ignored");
    }

    @Test
    void theStandardInputSourceReadsOneLineAndStripsOnlyTheLineTerminator() throws IOException {
        assertThat(readLine("a password with spaces \n")).containsExactly("a password with spaces ".toCharArray());
        assertThat(readLine("secret-secret-secret\r\n")).containsExactly("secret-secret-secret".toCharArray());
        assertThat(readLine("no-terminator-at-all")).containsExactly("no-terminator-at-all".toCharArray());
        assertThat(readLine("first-line-wins\nsecond-line\n")).containsExactly("first-line-wins".toCharArray());
    }

    @Test
    void theStandardInputSourceReadsUtf8() throws IOException {
        assertThat(readLine("contraseña-é\n")).containsExactly("contraseña-é".toCharArray());
    }

    @Test
    void anEmptyStandardInputIsNullNotAnEmptyPassword() throws IOException {
        assertThat(readLine("")).isNull();
    }

    @Test
    void theStandardInputSourceIsNotInteractive() {
        assertThat(AdminPasswordHashCommand.stdin(new ByteArrayInputStream(new byte[0])).interactive()).isFalse();
    }
}
