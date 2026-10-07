package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D-20261007-23: errors raised outside Spring MVC (Tomcat-level) render through Boot's {@code /error}. That body must
 * carry no stack trace, exception class, message or binding detail, whatever the request attributes hold.
 */
class BootErrorEndpointIT extends AbstractPostgresIT {

    private static final String SECRET = "boom: +5493510000000";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @Test
    void errorDetailsAreExplicitlyDisabled() {
        assertThat(environment.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-binding-errors")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-exception")).isEqualTo("false");
    }

    @Test
    void errorEndpointBodyHasNoTraceMessageOrExceptionName() throws Exception {
        String body = mvc.perform(get("/error").accept(MediaType.APPLICATION_JSON)
                        .requestAttr("jakarta.servlet.error.status_code", 500)
                        .requestAttr("jakarta.servlet.error.message", SECRET)
                        .requestAttr("jakarta.servlet.error.exception", new IllegalStateException(SECRET)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("+54", "boom", "IllegalStateException", "trace", "at ar.cetobhb");
    }
}
