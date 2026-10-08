package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.config.WebProperties;
import ar.cetobhb.support.AbstractServerIT;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The container, not the byte counter, parses form parameters: Tomcat reads them straight from the socket, behind the
 * counting wrapper, when anything calls {@code getParameter}. {@code server.tomcat.max-http-form-post-size} is what
 * stops a huge form there. A test-only filter calls {@code getParameter} (nothing in the application does today, which
 * is exactly why the limit must hold for the day something does) and the form is chunked so that the
 * {@link BodySizeLimitFilter} cannot reject it from a {@code Content-Length}.
 */
@Import(FormPostLimitServerIT.ParameterReaderConfig.class)
class FormPostLimitServerIT extends AbstractServerIT {

    private static final String READS_PARAMETERS = "/api/test-only/form";
    private static final int CHUNK = 4096;

    @TestConfiguration(proxyBeanMethods = false)
    static class ParameterReaderConfig {
        @Bean
        FilterRegistrationBean<Filter> parameterReaderFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                String value = request.getParameter("a");
                response.setContentType("text/plain");
                ((HttpServletResponse) response).setStatus(200);
                response.getWriter().write("parsed=" + (value == null ? "null" : value.length()));
            });
            registration.addUrlPatterns(READS_PARAMETERS);
            return registration;
        }
    }

    @Autowired
    private WebProperties web;

    private String chunkedFormPost(int valueLength) throws IOException {
        byte[] form = ("a=" + "x".repeat(valueLength)).getBytes(StandardCharsets.US_ASCII);
        StringBuilder request = new StringBuilder("POST " + READS_PARAMETERS + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nContent-Type: application/x-www-form-urlencoded\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n");
        for (int offset = 0; offset < form.length; offset += CHUNK) {
            int length = Math.min(CHUNK, form.length - offset);
            request.append(Integer.toHexString(length)).append("\r\n")
                    .append(new String(form, offset, length, StandardCharsets.US_ASCII)).append("\r\n");
        }
        request.append("0\r\n\r\n");
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(10_000);
            try {
                socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (IOException serverAlreadyAnswered) {
                // Acceptable: the server may close early once it answered; the reply is read below either way.
            }
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aSmallChunkedFormIsParsedSoTheProbeIsNotVacuous() throws IOException {
        String reply = chunkedFormPost(100);

        assertThat(reply).startsWith("HTTP/1.1 200").contains("parsed=100");
    }

    @Test
    void aChunkedFormOfThreeTimesTheBodyLimitIsNeverParsed() throws IOException {
        String reply = chunkedFormPost(web.maxBodyBytes() * 3);

        assertThat(reply).doesNotContain("parsed=" + web.maxBodyBytes() * 3);
        assertThat(reply).as("the connector refused the form").doesNotContain("HTTP/1.1 200");
    }
}
