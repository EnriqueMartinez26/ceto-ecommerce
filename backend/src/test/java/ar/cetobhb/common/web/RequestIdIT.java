package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The request-id filter is registered in the real filter chain and the error body repeats the header. */
class RequestIdIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Test
    void errorResponsesCarryTheHeaderAndTheSameIdInTheBody() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/no-such-route")
                .header("X-Request-Id", "attacker-value")).andReturn().getResponse();

        String header = response.getHeader("X-Request-Id");
        assertThat(header).isNotEqualTo("attacker-value");
        assertThat(UUID.fromString(header)).hasToString(header);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("requestId").asString()).isEqualTo(header);
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void everyRequestGetsItsOwnId() throws Exception {
        String first = mvc.perform(get("/no-such-route")).andReturn().getResponse().getHeader("X-Request-Id");
        String second = mvc.perform(get("/no-such-route")).andReturn().getResponse().getHeader("X-Request-Id");

        assertThat(first).isNotNull().isNotEqualTo(second);
    }
}
