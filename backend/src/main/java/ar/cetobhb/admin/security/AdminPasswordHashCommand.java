package ar.cetobhb.admin.security;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The provisioning command for {@code ADMIN_PASSWORD_HASH} (REQ-AUTH-01, AUTH-14): it turns a password into the
 * {@code {bcrypt}} value the application expects, at cost {@value #COST}. There is no reset endpoint; a new password is a
 * new hash and a redeploy.
 *
 * <p>Rules, all deliberate:
 * <ul>
 *   <li>The password comes from the terminal without echo ({@link Console#readPassword}, asked twice) or, when input is
 *       piped, from the first line of standard input. It is NEVER an argument: any argument is refused before anything
 *       is read, because a command line ends up in the shell history and in the process list.</li>
 *   <li>At least {@value #MIN_CHARACTERS} characters and at most {@value #MAX_BYTES} UTF-8 bytes: BCrypt reads no more
 *       than 72 bytes and the login refuses a longer password without hashing, so such a password could never log in.</li>
 *   <li>A line longer than {@value #MAX_LINE_BYTES} bytes is refused with a fixed message and exit code 1 before any
 *       of it is kept: standard input is read into a fixed-size buffer and no more than the limit (plus one byte) is
 *       consumed, so an endless stream cannot exhaust memory.</li>
 *   <li>Only the hash goes to standard output, as one line; prompts and errors go to standard error, and they never
 *       contain the password. Nothing is logged: the class does not use a logging framework.</li>
 *   <li>Best effort on memory: every buffer this class owns that held the password (the input bytes, the decoded
 *       characters, the terminal array) is overwritten before the command returns. BCrypt's encoder converts the
 *       characters to a {@code String} internally, and that copy cannot be wiped, so the guarantee is about the buffers
 *       of this class and about the output, not about the memory of the process.</li>
 *   <li>Interactive or piped is decided on a terminal check that is correct on Java 21 (the version this project
 *       targets, where {@code System.console()} is {@code null} as soon as input or output is redirected) and on later
 *       JDKs (where a console exists even then and {@code Console#isTerminal()} tells).</li>
 * </ul>
 *
 * <p>Run it from the application jar (the runbook is {@code docs/operacion-admin.md}):
 * {@code java -Dloader.main=ar.cetobhb.admin.security.AdminPasswordHashCommand -cp ceto-backend.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}.
 */
public final class AdminPasswordHashCommand {

    /** Cost of the emitted hash: inside the 10 to 14 that {@code AdminCredentials} accepts, the recommended value. */
    public static final int COST = 12;

    public static final int MIN_CHARACTERS = 14;

    public static final int MAX_BYTES = 72;

    /** Longest line accepted from either source, in bytes (terminal: in characters), line terminator not counted. */
    public static final int MAX_LINE_BYTES = 1024;

    static final String TOO_SHORT = "The password must have at least " + MIN_CHARACTERS + " characters.";
    static final String TOO_LONG = "The password must have at most " + MAX_BYTES + " bytes in UTF-8.";
    static final String LINE_TOO_LONG = "The input line is longer than " + MAX_LINE_BYTES + " bytes.";
    static final String MISMATCH = "The two entries differ.";
    static final String NO_INPUT = "No password was provided.";
    static final String USAGE = "This command takes no arguments: it reads the password from the terminal or from "
            + "standard input.";

    /** {@code Console#isTerminal()}, present from JDK 22; {@code null} on Java 21, where it does not exist. */
    private static final Method IS_TERMINAL = lookUpIsTerminal();

    /** Where the password comes from: a terminal (asked twice, no echo) or a piped line (asked once). */
    interface PasswordSource {

        /** True for a terminal: the password is asked twice to catch a typing mistake. */
        boolean interactive();

        /** The next password, as a buffer the caller wipes, or {@code null} when there is no more input. */
        char[] read(String prompt) throws IOException;
    }

    /** The input line is over {@value #MAX_LINE_BYTES} bytes. Carries no message: the line must not be repeated. */
    private static final class LineTooLongException extends IOException {

        private static final long serialVersionUID = 1L;

        LineTooLongException() {
            super();
        }
    }

    private AdminPasswordHashCommand() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        PasswordSource source = isTerminal(console) ? terminal(console) : stdin(System.in);
        System.exit(run(args, source, System.out, System.err));
    }

    /** Exit codes: 0 the hash was printed, 1 the password was refused, 2 the command was misused. */
    static int run(String[] args, PasswordSource source, PrintStream out, PrintStream err) {
        if (args.length != 0) {
            err.println(USAGE);
            return 2;
        }
        char[] first = null;
        char[] second = null;
        try {
            first = source.read("Password: ");
            if (first == null) {
                err.println(NO_INPUT);
                return 1;
            }
            if (first.length > MAX_LINE_BYTES) {
                err.println(LINE_TOO_LONG);
                return 1;
            }
            if (source.interactive()) {
                second = source.read("Repeat the password: ");
                if (second == null || !Arrays.equals(first, second)) {
                    err.println(MISMATCH);
                    return 1;
                }
            }
            String problem = problemWith(first);
            if (problem != null) {
                err.println(problem);
                return 1;
            }
            // An explicit LF: println would end the line with CRLF on Windows, and a stray CR inside ADMIN_PASSWORD_HASH
            // makes the application refuse to start (see the runbook).
            out.print("{bcrypt}" + new BCryptPasswordEncoder(COST).encode(CharBuffer.wrap(first)) + "\n");
            out.flush();
            return 0;
        } catch (LineTooLongException e) {
            err.println(LINE_TOO_LONG);
            return 1;
        } catch (IOException e) {
            // Only the type: the message of an I/O failure is not ours to repeat.
            err.println("The password could not be read (" + e.getClass().getSimpleName() + ").");
            return 1;
        } finally {
            wipe(first);
            wipe(second);
        }
    }

    /** The reason the password cannot be used, or {@code null} when it can. */
    static String problemWith(char[] password) {
        if (Character.codePointCount(password, 0, password.length) < MIN_CHARACTERS) {
            return TOO_SHORT;
        }
        if (utf8Length(password) > MAX_BYTES) {
            return TOO_LONG;
        }
        return null;
    }

    private static int utf8Length(char[] password) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        try {
            return bytes.remaining();
        } finally {
            if (bytes.hasArray()) {
                Arrays.fill(bytes.array(), (byte) 0);
            }
        }
    }

    /** True when the JVM has an interactive terminal to ask for a password on. */
    static boolean isTerminal(Console console) {
        return isTerminal(console, IS_TERMINAL);
    }

    /**
     * {@code console} is the object returned by {@code System.console()} and {@code isTerminal} is
     * {@code Console#isTerminal()} when the running JDK has it, otherwise {@code null}. On Java 21 a console exists only
     * when both input and output are terminals, so its mere existence answers; from JDK 22 a console also exists over
     * redirected streams and the method decides.
     */
    static boolean isTerminal(Object console, Method isTerminal) {
        if (console == null) {
            return false;
        }
        if (isTerminal == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(isTerminal.invoke(console));
        } catch (ReflectiveOperationException unreadable) {
            return true;
        }
    }

    private static Method lookUpIsTerminal() {
        try {
            return Console.class.getMethod("isTerminal");
        } catch (NoSuchMethodException java21) {
            return null;
        }
    }

    private static void wipe(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }

    private static PasswordSource terminal(Console console) {
        return new PasswordSource() {
            @Override
            public boolean interactive() {
                return true;
            }

            @Override
            public char[] read(String prompt) {
                return console.readPassword("%s", prompt);
            }
        };
    }

    /**
     * Standard input as a source: the first line, in UTF-8, without its line terminator ({@code \n} or {@code \r\n});
     * spaces are part of the password. An empty stream is {@code null}. A line over {@value #MAX_LINE_BYTES} bytes is
     * refused as soon as it is known to be over (one byte past the limit has been read), without keeping it.
     */
    static PasswordSource stdin(InputStream in) {
        return stdin(in, buffer -> { });
    }

    /** As {@link #stdin(InputStream)}; {@code afterWipe} sees the line buffer after it was overwritten (for tests). */
    static PasswordSource stdin(InputStream in, Consumer<byte[]> afterWipe) {
        return new PasswordSource() {
            @Override
            public boolean interactive() {
                return false;
            }

            @Override
            public char[] read(String prompt) throws IOException {
                // Room for the limit plus the CR of a CRLF terminator.
                byte[] line = new byte[MAX_LINE_BYTES + 1];
                try {
                    int length = 0;
                    boolean any = false;
                    int next;
                    while ((next = in.read()) != -1) {
                        any = true;
                        if (next == '\n') {
                            break;
                        }
                        if (length == line.length || (length == MAX_LINE_BYTES && next != '\r')) {
                            throw new LineTooLongException();
                        }
                        line[length++] = (byte) next;
                    }
                    if (!any) {
                        return null;
                    }
                    if (length > 0 && line[length - 1] == '\r') {
                        length--;
                    }
                    CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(line, 0, length));
                    char[] password = new char[decoded.remaining()];
                    decoded.get(password);
                    if (decoded.hasArray()) {
                        Arrays.fill(decoded.array(), '\0');
                    }
                    return password;
                } finally {
                    Arrays.fill(line, (byte) 0);
                    afterWipe.accept(line);
                }
            }
        };
    }
}
