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
    void aFilterWrittenProblemIsNeverCached() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        problems.write(new MockHttpServletRequest("POST", "/api/checkout/orders"), response,
                HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request is too large.");

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void aSecurityProblemIsWrittenWithItsFixedStatusCodeAndDetail() throws Exception {
        for (SecurityProblem problem : SecurityProblem.values()) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/orders");
            request.setAttribute("requestId", "rid-7");
            MockHttpServletResponse response = new MockHttpServletResponse();

            problems.write(request, response, problem);

            assertThat(response.getStatus()).isEqualTo(problem.status().value());
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            JsonNode body = json.readTree(response.getContentAsString());
            assertThat(body.get("code").asString()).isEqualTo(problem.code());
            assertThat(body.get("detail").asString()).isEqualTo(problem.detail());
            assertThat(body.get("status").asInt()).isEqualTo(problem.status().value());
            assertThat(body.get("title").asString()).isEqualTo(problem.status().getReasonPhrase());
            assertThat(body.get("instance").asString()).isEqualTo("/api/admin/orders");
            assertThat(body.get("requestId").asString()).isEqualTo("rid-7");
            assertThat(body.has("type")).isFalse();
        }
    }

    @Test
    void aSecurityProblemNeverEchoesTheQueryStringOrAnyHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/login");
        request.setQueryString("username=sentinel-name&password=sentinel-pass");
        request.addHeader("Authorization", "Bearer sentinel-token");
        request.addHeader("Cookie", "JSESSIONID=sentinel-session");
        request.addParameter("password", "sentinel-pass");
        MockHttpServletResponse response = new MockHttpServletResponse();

        problems.write(request, response, SecurityProblem.UNAUTHENTICATED);

        assertThat(response.getContentAsString()).doesNotContain("sentinel");
    }

    @Test
    void instanceIsNeverNullEvenForAnUnparsablePath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        problems.write(request, response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");

        assertThat(json.readTree(response.getContentAsString()).get("instance").asString()).isEqualTo("/");
    }
}
