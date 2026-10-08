package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No administrator password hash lives in the repository (REQ-AUTH-01). The scan covers EVERY tracked text file of the
 * repository (the list comes from {@code git ls-files}; outside a git checkout it falls back to a walk of the
 * repository root that skips {@code .git}, {@code target} and {@code node_modules}), not a list of file names: a hash
 * pasted into a runbook, a script or a compose file is found the same way.
 *
 * <p>Two shapes are detected, each with or without the {@code $$} escaping that compose files need: a hash prefixed
 * with {@code {bcrypt}}, and a bare BCrypt hash (a {@code $2a$} / {@code $2b$} / {@code $2y$} prefix, a two digit cost
 * and 53 characters of the BCrypt alphabet). This file is excluded from the scan because it has to spell the patterns,
 * and binary files (a NUL byte in the first 8000 bytes) are skipped.
 */
class NoCommittedHashTest {

    /** The prefixed form, e.g. a {@code {bcrypt}} marker followed by {@code $2a} or {@code $$2a}. */
    private static final Pattern PREFIXED = Pattern.compile("\\{bcrypt}\\$\\$?2[aby]");

    /** A bare BCrypt hash, with or without compose escaping of each {@code $}. */
    private static final Pattern BARE = Pattern.compile("\\$\\$?2[aby]\\$\\$?\\d\\d\\$\\$?[./A-Za-z0-9]{53}");

    private static final Path BACKEND = Path.of("").toAbsolutePath();

    private static final Path ROOT = BACKEND.getParent();

    private static final Path SELF = ROOT.resolve("backend/src/test/java/ar/cetobhb/admin/security/NoCommittedHashTest.java");

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target", "node_modules", ".next");

    static boolean containsHash(String content) {
        return PREFIXED.matcher(content).find() || BARE.matcher(content).find();
    }

    private static boolean isBinary(byte[] content) {
        int limit = Math.min(content.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    static List<Path> offenders(List<Path> files, Path base) throws IOException {
        List<Path> offenders = new ArrayList<>();
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            if (isBinary(bytes)) {
                continue;
            }
            if (containsHash(new String(bytes, StandardCharsets.ISO_8859_1))) {
                offenders.add(base.relativize(file));
            }
        }
        return offenders;
    }

    /** Every tracked file of the repository except this test. */
    static List<Path> trackedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        List<String> listed = gitLsFiles();
        if (listed != null) {
            for (String name : listed) {
                Path file = ROOT.resolve(name);
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } else {
            try (Stream<Path> walk = Files.walk(ROOT)) {
                walk.filter(Files::isRegularFile).filter(NoCommittedHashTest::notInASkippedDirectory)
                        .forEach(files::add);
            }
        }
        files.removeIf(file -> file.toAbsolutePath().normalize().equals(SELF.toAbsolutePath().normalize()));
        return files;
    }

    private static boolean notInASkippedDirectory(Path file) {
        for (Path part : ROOT.relativize(file)) {
            if (SKIPPED_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** The tracked paths, or null when git is not available or this is not a checkout. */
    private static List<String> gitLsFiles() {
        try {
            Process process = new ProcessBuilder("git", "ls-files", "-z").directory(ROOT.toFile())
                    .redirectErrorStream(false).start();
            byte[] out = process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
            if (process.waitFor() != 0 || out.length == 0) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (String name : new String(out, StandardCharsets.UTF_8).split("\0")) {
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static final String BODY53 = "a".repeat(53);

    @Test
    void noTrackedTextFileContainsABcryptHash() throws IOException {
        assertThat(offenders(trackedFiles(), ROOT)).as("files that contain a committed bcrypt hash").isEmpty();
    }

    @Test
    void theScanCoversTheWholeRepositoryNotAFileNameWhitelist() throws IOException {
        List<String> names = trackedFiles().stream().map(file -> ROOT.relativize(file).toString().replace('\\', '/'))
                .toList();

        assertThat(names).hasSizeGreaterThan(100);
        assertThat(names).contains("CLAUDE.md", "docs/DECISIONES.md", "backend/pom.xml");
        assertThat(names).anyMatch(name -> name.startsWith("backend/src/main/java/"));
        assertThat(names).doesNotContain("backend/src/test/java/ar/cetobhb/admin/security/NoCommittedHashTest.java");
    }

    @Test
    void theEscapedDollarFormOfAComposeFileIsDetected(@TempDir Path dir) throws IOException {
        Path planted = dir.resolve("compose.yml");
        Files.writeString(planted, "ADMIN_PASSWORD_HASH: {bcrypt}$$2a$$12$$" + BODY53 + "\n");

        assertThat(offenders(List.of(planted), dir)).containsExactly(Path.of("compose.yml"));
    }

    @Test
    void aBareHashWithoutThePrefixIsDetected(@TempDir Path dir) throws IOException {
        Path planted = dir.resolve("notes.txt");
        Files.writeString(planted, "hash=$2b$12$" + BODY53 + "\n");
        Path escaped = dir.resolve("other.txt");
        Files.writeString(escaped, "hash=$$2y$$10$$" + BODY53 + "\n");

        assertThat(offenders(List.of(planted, escaped), dir)).containsExactly(Path.of("notes.txt"),
                Path.of("other.txt"));
    }

    @Test
    void aHashInsideAMarkdownFileAndAShellScriptIsDetected(@TempDir Path dir) throws IOException {
        Path markdown = dir.resolve("runbook.md");
        Files.writeString(markdown, "# Runbook\n\nUse `{bcrypt}$2a$12$" + BODY53 + "` as the value.\n");
        Path script = dir.resolve("deploy.sh");
        Files.writeString(script, "#!/bin/sh\nexport ADMIN_PASSWORD_HASH='$2a$12$" + BODY53 + "'\n");

        assertThat(offenders(List.of(markdown, script), dir)).containsExactly(Path.of("runbook.md"),
                Path.of("deploy.sh"));
    }

    @Test
    void documentationOfTheFormatWithoutARealHashIsNotAnOffender(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("doc.md");
        Files.writeString(doc, "The value is {bcrypt} plus the hash, which starts with $2a$12$ and has 53 more characters; "
                + "in compose each dollar is written twice.\n");

        assertThat(offenders(List.of(doc), dir)).isEmpty();
    }

    @Test
    void aBinaryFileIsSkipped(@TempDir Path dir) throws IOException {
        Path binary = dir.resolve("image.bin");
        byte[] head = new byte[] {0, 1, 2, 3};
        byte[] text = ("$2a$12$" + BODY53).getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[head.length + text.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(text, 0, all, head.length, text.length);
        Files.write(binary, all);

        assertThat(offenders(List.of(binary), dir)).isEmpty();
    }
}
