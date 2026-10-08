package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The command as an operator runs it: a separate JVM, the class launched through its {@code main}, the password piped to
 * standard input. It checks the contract of the process (exit codes, what goes to which stream) that the in-process
 * tests cannot: a stray byte on standard output would end up inside {@code ADMIN_PASSWORD_HASH}.
 */
class AdminPasswordHashCommandProcessTest {

    private static final String PASSWORD = "S3ntinel-Pass-Qw83!";

    private record Outcome(int exit, String out, String err) {
    }

    private static Outcome launch(String stdin, String... args) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = new java.util.ArrayList<>(
                List.of(java, "-cp", System.getProperty("java.class.path"), AdminPasswordHashCommand.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Outcome(process.exitValue(), new String(out, StandardCharsets.UTF_8),
                new String(err, StandardCharsets.UTF_8));
    }

    @Test
    void aPipedPasswordGivesExactlyOneLineWithTheHashOnStandardOutputAndNothingOnStandardError() throws Exception {
        Outcome outcome = launch(PASSWORD + "\n");

        assertThat(outcome.exit()).isZero();
        assertThat(outcome.err()).isEmpty();
        assertThat(outcome.out()).matches("\\{bcrypt\\}\\$2a\\$12\\$[./A-Za-z0-9]{53}\\n");
        String hash = outcome.out().strip().substring("{bcrypt}".length());
        assertThat(new BCryptPasswordEncoder(12).matches(PASSWORD, hash)).isTrue();
        assertThat(outcome.out()).doesNotContain(PASSWORD);
    }

    @Test
    void aPipedPasswordThatIsTooShortExitsOneWithNothingOnStandardOutput() throws Exception {
        Outcome outcome = launch("too short\n");

        assertThat(outcome.exit()).isEqualTo(1);
        assertThat(outcome.out()).isEmpty();
        assertThat(outcome.err()).contains("at least 14 characters").doesNotContain("too short");
    }

    @Test
    void aPasswordGivenAsAnArgumentExitsTwoAndIsNeverEchoed() throws Exception {
        Outcome outcome = launch(PASSWORD + "\n", PASSWORD);

        assertThat(outcome.exit()).isEqualTo(2);
        assertThat(outcome.out()).isEmpty();
        assertThat(outcome.err()).contains("takes no arguments").doesNotContain(PASSWORD);
    }

    @Test
    void aPipedLineOverOneKilobyteExitsOneWithTheFixedMessageAndNothingEchoed() throws Exception {
        Outcome outcome = launch("S3ntinel-" + "x".repeat(5_000) + "\n");

        assertThat(outcome.exit()).isEqualTo(1);
        assertThat(outcome.out()).isEmpty();
        assertThat(outcome.err()).isEqualToIgnoringNewLines(AdminPasswordHashCommand.LINE_TOO_LONG);
        assertThat(outcome.err()).doesNotContain("S3ntinel").doesNotContain("xxxx");
    }

    @Test
    void anEmptyStandardInputExitsOne() throws Exception {
        Outcome outcome = launch("");

        assertThat(outcome.exit()).isEqualTo(1);
        assertThat(outcome.out()).isEmpty();
    }
}
