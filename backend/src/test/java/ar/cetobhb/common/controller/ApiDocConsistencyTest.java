package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.domain.ReasonCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code docs/API.md} honest (D-20261007-18). The document is written by hand, so these checks fail the build
 * when it drifts from the contract: every example introduced by a {@code Golden:} line is identical to that golden
 * file, every golden file is cited, and every error {@code code} and every {@code reason} the code can produce is
 * listed. It reads the files from the module directory (Maven runs the tests with {@code backend/} as the working
 * directory).
 */
class ApiDocConsistencyTest {

    private static final Path DOC = Path.of("..", "docs", "API.md");
    private static final Path CONTRACT_DIR = Path.of("src", "test", "resources", "contract");
    private static final String CONTRACT_PREFIX = "backend/src/test/resources/contract/";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern GOLDEN_BLOCK = Pattern.compile(
            "Golden: `" + CONTRACT_PREFIX + "([a-z0-9-]+\\.json)`\\R\\R```json\\R(.*?)\\R```", Pattern.DOTALL);

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), e);
        }
    }

    /** Names of the golden files the document cites, in order, with the example it shows for each. */
    record Example(String golden, String json) {
    }

    static List<Example> examplesOf(String markdown) {
        List<Example> examples = new ArrayList<>();
        Matcher matcher = GOLDEN_BLOCK.matcher(markdown);
        while (matcher.find()) {
            examples.add(new Example(matcher.group(1), matcher.group(2)));
        }
        return examples;
    }

    /** Examples whose JSON differs from the golden file of the same name (or whose file does not exist). */
    static List<String> mismatches(String markdown, Path contractDir) {
        List<String> problems = new ArrayList<>();
        for (Example example : examplesOf(markdown)) {
            Path file = contractDir.resolve(example.golden());
            if (!Files.exists(file)) {
                problems.add(example.golden() + ": the golden file does not exist");
                continue;
            }
            try {
                JsonNode shown = JSON.readTree(example.json());
                JsonNode actual = JSON.readTree(read(file));
                if (!shown.equals(actual)) {
                    problems.add(example.golden() + ": the example differs from the golden file");
                }
            } catch (RuntimeException e) {
                problems.add(example.golden() + ": the example is not valid JSON");
            }
        }
        return problems;
    }

    static Set<String> goldenFiles(Path contractDir) {
        try (Stream<Path> files = Files.list(contractDir)) {
            Set<String> names = new TreeSet<>();
            files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json"))
                    .forEach(names::add);
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every (status, code) pair the API can answer, as the "STATUS CODE" form used in reports and table rows. */
    static Set<String> allErrorRows() {
        Set<String> rows = new TreeSet<>();
        ApiErrorCatalog.domainErrors().values().forEach(error -> rows.add(rowOf(error)));
        ApiErrorCatalog.securityRows().values().forEach(error -> rows.add(rowOf(error)));
        for (ApiError error : List.of(ApiErrorCatalog.VALIDATION, ApiErrorCatalog.MALFORMED,
                ApiErrorCatalog.UNSUPPORTED_MEDIA_TYPE, ApiErrorCatalog.NOT_ACCEPTABLE,
                ApiErrorCatalog.METHOD_NOT_ALLOWED, ApiErrorCatalog.NOT_FOUND, ApiErrorCatalog.PAYLOAD_TOO_LARGE,
                ApiErrorCatalog.CONFLICT, ApiErrorCatalog.SERVICE_BUSY, ApiErrorCatalog.INTERNAL,
                ApiErrorCatalog.clientError(org.springframework.http.HttpStatus.BAD_REQUEST))) {
            rows.add(rowOf(error));
        }
        return rows;
    }

    private static String rowOf(ApiError error) {
        return error.status().value() + " " + error.code();
    }

    /**
     * Pairs ("STATUS CODE") with no row of the form {@code | STATUS | `CODE` |} in the document: a code that is only
     * mentioned in prose, or listed under another status, does not count.
     */
    static List<String> missingErrorCodes(String markdown) {
        List<String> missing = new ArrayList<>();
        for (String row : allErrorRows()) {
            String[] parts = row.split(" ");
            Pattern tableRow = Pattern.compile("(?m)^\\| " + parts[0] + " \\| `" + parts[1] + "` \\|");
            if (!tableRow.matcher(markdown).find()) {
                missing.add(row);
            }
        }
        return missing;
    }

    /** Reasons the document does not mention. */
    static List<String> missingReasons(String markdown) {
        List<String> missing = new ArrayList<>();
        for (ReasonCode reason : ReasonCode.values()) {
            if (!markdown.contains("`" + reason.name() + "`")) {
                missing.add(reason.name());
            }
        }
        return missing;
    }

    private static final Pattern RETRY_AFTER_VALUE = Pattern.compile("`Retry-After: (\\d+)`");

    /**
     * Retry-After values the document pins that differ from what the advice sends: the document must show the
     * per-phone busy value and the lock/pool value, and no other number.
     */
    static List<String> retryAfterProblems(String markdown) {
        Set<String> shown = new TreeSet<>();
        Matcher matcher = RETRY_AFTER_VALUE.matcher(markdown);
        while (matcher.find()) {
            shown.add(matcher.group(1));
        }
        Set<String> expected = new TreeSet<>(List.of(ApiExceptionHandler.CHECKOUT_IN_PROGRESS_RETRY_AFTER_SECONDS,
                ApiExceptionHandler.RETRY_AFTER_SECONDS));
        List<String> problems = new ArrayList<>();
        if (!shown.equals(expected)) {
            problems.add("documented Retry-After values " + shown + " but the advice sends " + expected);
        }
        return problems;
    }

    // ---- the real document ----

    @Test
    void everyExampleIsIdenticalToItsGoldenFile() {
        String doc = read(DOC);

        assertThat(examplesOf(doc)).as("examples introduced by a Golden: line").isNotEmpty();
        assertThat(mismatches(doc, CONTRACT_DIR)).isEmpty();
    }

    @Test
    void everyGoldenFileIsShownInTheDocument() {
        Set<String> cited = new TreeSet<>();
        examplesOf(read(DOC)).forEach(example -> cited.add(example.golden()));

        assertThat(goldenFiles(CONTRACT_DIR)).as("golden files in " + CONTRACT_DIR).isNotEmpty()
                .isEqualTo(cited);
    }

    @Test
    void everyErrorCodeOfTheCatalogIsListed() {
        assertThat(allErrorRows()).hasSizeGreaterThanOrEqualTo(16);
        assertThat(missingErrorCodes(read(DOC))).isEmpty();
    }

    @Test
    void everyReasonCodeIsListed() {
        assertThat(missingReasons(read(DOC))).isEmpty();
    }

    @Test
    void theDocumentedRetryAfterValuesMatchTheAdvice() {
        assertThat(retryAfterProblems(read(DOC))).isEmpty();
    }

    // ---- the checks themselves bite ----

    @Test
    void anExampleThatDriftsFromItsGoldenFileIsReported() {
        String drifted = """
                Golden: `backend/src/test/resources/contract/error-not-found.json`

                ```json
                {"title": "Not Found", "status": 404, "detail": "changed", "instance": "/api/checkout/unknown",
                 "code": "NOT_FOUND"}
                ```
                """;

        assertThat(mismatches(drifted, CONTRACT_DIR))
                .containsExactly("error-not-found.json: the example differs from the golden file");
    }

    @Test
    void anExampleCitingAMissingFileIsReported() {
        String missing = """
                Golden: `backend/src/test/resources/contract/nope.json`

                ```json
                {}
                ```
                """;

        assertThat(mismatches(missing, CONTRACT_DIR)).containsExactly("nope.json: the golden file does not exist");
    }

    @Test
    void aCatalogCodeMissingFromTheErrorTableIsReported() {
        String doc = read(DOC);

        assertThat(missingErrorCodes(doc)).isEmpty();
        assertThat(missingErrorCodes(doc.replace("| 409 | `INSUFFICIENT_STOCK` |", "| 409 | `RENAMED` |")))
                .containsExactly("409 INSUFFICIENT_STOCK");
    }

    @Test
    void aCodeMentionedOnlyInProseIsNotARow() {
        String doc = read(DOC);
        String proseOnly = doc.replace("| 405 | `METHOD_NOT_ALLOWED` |", "| 405 | METHOD_NOT_ALLOWED |")
                + "\nMETHOD_NOT_ALLOWED is mentioned as `METHOD_NOT_ALLOWED` here.\n";

        assertThat(missingErrorCodes(proseOnly)).containsExactly("405 METHOD_NOT_ALLOWED");
    }

    @Test
    void aCodeListedWithAnotherStatusIsReported() {
        String doc = read(DOC).replace("| 503 | `SERVICE_BUSY` |", "| 500 | `SERVICE_BUSY` |");

        assertThat(missingErrorCodes(doc)).containsExactly("503 SERVICE_BUSY");
    }

    @Test
    void aReasonMissingFromTheDocumentIsReported() {
        String doc = read(DOC);

        assertThat(missingReasons(doc)).isEmpty();
        assertThat(missingReasons(doc.replace("`EMPTY_CART`", "`NOT_A_REASON`"))).containsExactly("EMPTY_CART");
    }

    @Test
    void theDocumentedRetryAfterValuesAreTheOnesTheAdviceSends() {
        String doc = read(DOC);

        assertThat(retryAfterProblems(doc)).isEmpty();
        assertThat(retryAfterProblems(doc.replace("`Retry-After: 1`", "`Retry-After: 2`"))).isNotEmpty();
        assertThat(retryAfterProblems(doc.replace("`Retry-After: 5`", "`Retry-After: 9`"))).isNotEmpty();
        assertThat(retryAfterProblems(doc.replace("`Retry-After: 5`", "`Retry-After: 5` y `Retry-After: 7`")))
                .isNotEmpty();
    }

    @Test
    void formattingAndKeyOrderDoNotCount() {
        String reordered = """
                Golden: `backend/src/test/resources/contract/error-not-found.json`

                ```json
                {"code":"NOT_FOUND","instance":"/api/checkout/unknown","detail":"The resource was not found.",
                 "status":404,"title":"Not Found"}
                ```
                """;

        assertThat(mismatches(reordered, CONTRACT_DIR)).isEmpty();
    }
}
