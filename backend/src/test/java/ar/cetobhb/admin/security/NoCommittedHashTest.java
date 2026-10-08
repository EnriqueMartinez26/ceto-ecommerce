package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No administrator password hash lives in the repository (REQ-AUTH-01). The scan covers every file under
 * {@code backend/src} and the deployment files at the repository root (compose files, env files). The needle is built
 * from two pieces so that this file does not contain it.
 */
class NoCommittedHashTest {

    private static final String NEEDLE = "{bcrypt" + "}$2";

    private static final Path BACKEND = Path.of("").toAbsolutePath();

    private static List<Path> filesToScan() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(BACKEND.resolve("src"))) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        Path root = BACKEND.getParent();
        try (Stream<Path> top = Files.list(root)) {
            top.filter(Files::isRegularFile).filter(NoCommittedHashTest::isDeploymentFile).forEach(files::add);
        }
        // Backend-level deployment files too (a Dockerfile or env file may live next to the pom).
        try (Stream<Path> top = Files.list(BACKEND)) {
            top.filter(Files::isRegularFile).filter(NoCommittedHashTest::isDeploymentFile).forEach(files::add);
        }
        return files;
    }

    private static boolean isDeploymentFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith(".env") || name.endsWith(".env") || name.contains("compose") || name.endsWith(".yml")
                || name.endsWith(".yaml") || name.endsWith(".properties") || name.startsWith("dockerfile");
    }

    @Test
    void noSourceOrDeploymentFileContainsABcryptHashLiteral() throws IOException {
        List<Path> offenders = new ArrayList<>();
        for (Path file : filesToScan()) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            if (content.contains(NEEDLE)) {
                offenders.add(BACKEND.relativize(file));
            }
        }

        assertThat(offenders).as("files that contain a committed {bcrypt} hash").isEmpty();
    }

    @Test
    void theScanReallyReadsTheSourcesAndTheNeedleBites(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        assertThat(filesToScan()).as("the scan sees the sources").hasSizeGreaterThan(100);
        Path planted = dir.resolve("compose.yml");
        Files.writeString(planted, "ADMIN_PASSWORD_HASH: " + NEEDLE + "a$10$abc");

        assertThat(isDeploymentFile(planted)).isTrue();
        assertThat(Files.readString(planted)).contains(NEEDLE);
    }
}
