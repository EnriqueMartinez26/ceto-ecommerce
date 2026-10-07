package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Filters write problem bodies themselves; the shape must match the advice (top-level extension members). */
class ProblemResponsesIT extends AbstractPostgresIT {

    @Autowired
    private ProblemResponses problems;

    @Autowired
    private JsonMapper json;

    @Test
    void writesTheSameShapeAsTheAdvice() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/checkout/orders");
        request.setAttribute("requestId", "rid-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        problems.write(request, response, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "The request is too large.");

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.get("requestId").asString()).isEqualTo("rid-1");
        assertThat(body.get("instance").asString()).isEqualTo("/api/checkout/orders");
        assertThat(body.get("status").asInt()).isEqualTo(413);
        assertThat(body.has("properties")).isFalse();
        assertThat(body.has("type")).isFalse();
    }

    @Test
    void instanceIsNeverNullEvenForAnUnparsablePath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        problems.write(request, response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");

        assertThat(json.readTree(response.getContentAsString()).get("instance").asString()).isEqualTo("/");
    }
}
