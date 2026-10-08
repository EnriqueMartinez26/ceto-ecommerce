package ar.cetobhb.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Golden-file contract checks. The only volatile member, {@code requestId}, is verified against the
 * {@code X-Request-Id} header (a UUID) and then stripped; everything else must match the file in STRICT mode, so an
 * extra, missing or reordered-type property fails the test.
 */
public final class ContractAssertions {

    private static final ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

    private ContractAssertions() {
    }

    public static void assertMatchesGolden(MockHttpServletResponse response, String goldenName) throws Exception {
        JSONAssert.assertEquals(golden(goldenName), stripRequestId(response), JSONCompareMode.STRICT);
    }

    /**
     * Golden check for the problem bodies of the administration area, whose {@code instance} is the requested path and
     * so differs per route: the golden has no {@code instance}; here the member must equal {@code expectedPath} and is
     * then stripped, like {@code requestId}.
     */
    public static void assertMatchesGoldenForPath(MockHttpServletResponse response, String goldenName,
            String expectedPath) throws Exception {
        assertMatchesGoldenForPath(response.getContentAsString(StandardCharsets.UTF_8),
                response.getHeader("X-Request-Id"), goldenName, expectedPath);
    }

    /** Same as above for a body and request id header that were read off a real HTTP exchange. */
    public static void assertMatchesGoldenForPath(String body, String requestIdHeader, String goldenName,
            String expectedPath) throws Exception {
        JsonNode node = MAPPER.readTree(body);
        ObjectNode object = (ObjectNode) node;
        assertThat(object.get("instance").asString()).as("instance").isEqualTo(expectedPath);
        object.remove("instance");
        assertThat(object.has("requestId")).as("requestId member").isTrue();
        String requestId = object.get("requestId").asString();
        assertThat(UUID.fromString(requestId)).hasToString(requestId);
        assertThat(requestId).as("requestId equals X-Request-Id").isEqualTo(requestIdHeader);
        object.remove("requestId");
        JSONAssert.assertEquals(golden(goldenName), MAPPER.writeValueAsString(node), JSONCompareMode.STRICT);
    }

    /** The response body without {@code requestId}, after checking that it equals the response header. */
    public static String stripRequestId(MockHttpServletResponse response) throws Exception {
        JsonNode node = MAPPER.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        if (node instanceof ObjectNode object && object.has("requestId")) {
            String requestId = object.get("requestId").asString();
            assertThat(UUID.fromString(requestId)).hasToString(requestId);
            assertThat(requestId).isEqualTo(response.getHeader("X-Request-Id"));
            object.remove("requestId");
        }
        return MAPPER.writeValueAsString(node);
    }

    private static String golden(String name) throws IOException {
        try (InputStream in = ContractAssertions.class.getResourceAsStream("/contract/" + name)) {
            assertThat(in).as("golden file contract/" + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
