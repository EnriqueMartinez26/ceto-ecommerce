package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractServerIT;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * A REAL container error dispatch still reaches Boot's {@code /error} (D-20261007-23) even though a direct request to
 * it is a 404. A test-only filter calls {@code sendError}, so Tomcat forwards to the error page with the ERROR
 * dispatcher type; the response must be the generic body, with no trace, message or exception.
 */
@Import(ErrorDispatchServerIT.SendErrorConfig.class)
class ErrorDispatchServerIT extends AbstractServerIT {

    private static final String SENDS_ERROR = "/api/test-only/send-error";

    @TestConfiguration(proxyBeanMethods = false)
    static class SendErrorConfig {
        @Bean
        FilterRegistrationBean<Filter> sendErrorFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) ->
                    ((HttpServletResponse) response).sendError(503, "internal detail that must not leak"));
            registration.addUrlPatterns(SENDS_ERROR);
            return registration;
        }
    }

    @Test
    void aRealErrorDispatchRendersTheGenericErrorBody() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + SENDS_ERROR))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> reply = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(reply.statusCode()).isEqualTo(503);
        assertThat(reply.body()).contains("\"status\":503")
                .doesNotContain("trace", "exception", "message", "internal detail", "at ar.cetobhb");
    }
}
