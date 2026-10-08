package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ar.cetobhb.common.web.ProblemResponses;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review fix batch 7, finding check: {@link WebConfig} reads the remote IP valve settings with
 * {@code Environment.getProperty} of the canonical (kebab-case) key. The question was whether a differently spelled
 * override (camelCase command-line argument) slips past that read while Boot's binder still applies it to Tomcat. The
 * real {@code SpringApplication} attaches the configuration property sources to the environment, so the canonical
 * lookup sees every relaxed spelling; this test pins that on the real startup path with the real application.yml
 * (the unit tests in {@code WebConfigTest} only cover environment variables).
 */
class WebConfigRelaxedArgsStartupTest {

    @Configuration(proxyBeanMethods = false)
    @Import(WebConfig.class)
    @EnableConfigurationProperties(WebProperties.class)
    static class App {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ProblemResponses problemResponses() {
            return new ProblemResponses(JsonMapper.builder().build());
        }
    }

    /** Command-line arguments beat application.yml (SpringApplicationBuilder.properties would sit below it). */
    private static ConfigurableApplicationContext run(String... overrides) {
        String[] arguments = Stream.concat(Stream.of("--TRUSTED_PROXIES=10.0.0.0/8"), Stream.of(overrides))
                .toArray(String[]::new);
        return new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF)
                .run(arguments);
    }

    @Test
    void theShippedConfigurationStartsSoTheOverridesBelowAreTheOnlyDifference() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBean(WebConfig.class)).isNotNull();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"--server.tomcat.remoteip.trustedProxies=10.0.0.1",
            "--server.tomcat.remoteip.trusted-proxies=10.0.0.1", "--server.tomcat.remoteip.TRUSTEDPROXIES=10.0.0.1",
            "--server.tomcat.remoteip.internalProxies=.*", "--server.tomcat.remoteip.INTERNAL_PROXIES=.*",
            "--server.tomcat.remoteip.remoteIpHeader=X-Real-IP", "--server.tomcat.remoteip.hostHeader=X-Forwarded-Host",
            "--server.tomcat.remoteip.portHeader=X-Forwarded-Port",
            "--server.tomcat.remoteip.protocolHeaderHttpsValue=https",
            "--server.tomcat.remoteip.protocolHeader=X-Forwarded-Scheme", "--server.forwardHeadersStrategy=framework"})
    void aDifferentlySpelledOverrideStopsTheStartup(String argument) {
        Throwable failure = catchThrowable(() -> run(argument).close());

        assertThat(failure).as(argument).isNotNull();
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        assertThat(all.toString()).as(argument).containsAnyOf("server.tomcat.remoteip", "server.forward-headers-strategy");
    }
}
