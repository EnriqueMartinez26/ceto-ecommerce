package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.support.AbstractHttpIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Endpoint inventory (REQ-HRD-03). Spring Security is deferred (D-20261007-15), so this allowlist is the compensating
 * control: the union of every MVC handler mapping must be exactly the three public API routes plus Boot's own error
 * endpoint, and nothing else (no actuator, documentation, static resources or welcome page) may answer.
 *
 * <p>Boot's {@link BasicErrorController} maps {@code /error} for every HTTP method (verification S1a review S8). It is
 * allowlisted explicitly BY HANDLER TYPE and path, apart from the API routes, so a new controller can never hide
 * behind it; what it renders is pinned by {@code BootErrorEndpointIT} (no trace, message or exception).
 */
class EndpointInventoryIT extends AbstractHttpIT {

    private static final Set<String> API_ROUTES = Set.of(
            "GET /api/store/products", "GET /api/store/products/{id}", "POST /api/checkout/orders");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private ApplicationContext context;

    private record Mapped(String route, Class<?> handler) {
    }

    private List<Mapped> mappings() {
        List<Mapped> mapped = new ArrayList<>();
        for (RequestMappingInfoHandlerMapping mapping
                : context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = entry.getKey();
                Set<String> paths = info.getPathPatternsCondition() == null
                        ? Set.of("?") : info.getPathPatternsCondition().getPatternValues();
                Set<String> methods = info.getMethodsCondition().getMethods().isEmpty()
                        ? Set.of("ANY")
                        : info.getMethodsCondition().getMethods().stream().map(Enum::name)
                                .collect(java.util.stream.Collectors.toSet());
                for (String method : methods) {
                    for (String path : paths) {
                        mapped.add(new Mapped(method + " " + path, entry.getValue().getBeanType()));
                    }
                }
            }
        }
        return mapped;
    }

    @Test
    void theApiRoutesAreExactlyTheThreePublicOnes() {
        Set<String> routes = new TreeSet<>();
        mappings().stream().filter(m -> m.handler() != BasicErrorController.class)
                .forEach(m -> routes.add(m.route()));

        assertThat(routes).containsExactlyInAnyOrderElementsOf(API_ROUTES);
    }

    @Test
    void theOnlyOtherMappingIsBootsErrorEndpointAndNothingElseSharesItsHandler() {
        List<Mapped> boot = mappings().stream()
                .filter(m -> m.handler() == BasicErrorController.class).toList();

        // BasicErrorController maps /error twice (text/html and everything else), for any HTTP method.
        assertThat(boot).isNotEmpty().allSatisfy(m -> assertThat(m.route()).isEqualTo("ANY /error"));
        assertThat(boot).extracting(Mapped::handler).containsOnly(BasicErrorController.class);
    }

    /** The URL-based mappings Boot and Spring register by default; each must have nothing mapped. */
    private static final Set<String> EMPTY_URL_MAPPINGS = Set.of(
            "org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping",
            "org.springframework.web.servlet.handler.SimpleUrlHandlerMapping",
            "org.springframework.boot.webmvc.autoconfigure.WelcomePageHandlerMapping",
            "org.springframework.boot.webmvc.autoconfigure.WelcomePageNotAcceptableHandlerMapping");

    @Test
    void everyHandlerMappingBeanIsOfAnAllowlistedTypeAndTheOthersAreEmpty() {
        Map<String, HandlerMapping> all = context.getBeansOfType(HandlerMapping.class);
        assertThat(all).isNotEmpty();
        all.forEach((name, mapping) -> {
            Class<?> type = mapping.getClass();
            if (type == RequestMappingHandlerMapping.class) {
                return;
            }
            if (type == RouterFunctionMapping.class) {
                assertThat(((RouterFunctionMapping) mapping).getRouterFunction())
                        .as("%s has no router function", name).isNull();
                return;
            }
            assertThat(type.getName()).as("handler mapping bean '%s'", name).isIn(EMPTY_URL_MAPPINGS);
            AbstractUrlHandlerMapping urlMapping = (AbstractUrlHandlerMapping) mapping;
            assertThat(urlMapping.getHandlerMap()).as("%s handler map", name).isEmpty();
            assertThat(urlMapping.getRootHandler()).as("%s welcome page / root handler", name).isNull();
        });
    }

    @Test
    void thereIsNoFunctionalRouterFunctionBean() {
        assertThat(context.getBeanNamesForType(RouterFunction.class)).isEmpty();
    }

    @Test
    void thereIsNoCorsFilterOrUrlBasedConfigurationInTheContext() {
        // Same-origin (D-20261007-14): a CORS grant needs one of these beans (or @CrossOrigin, banned by ArchUnit).
        assertThat(context.getBeanNamesForType(CorsFilter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(UrlBasedCorsConfigurationSource.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/swagger-ui.html", "/v3/api-docs", "/api/admin/x",
            "/api/store/products/1/anything", "/index.html", "/favicon.ico", "/"})
    void unknownPathsAreTheStandard404(String path) throws Exception {
        MockHttpServletResponse response = send(get(path));

        assertThat(response.getStatus()).isEqualTo(404);
        JsonNode body = JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(body.has("type")).isFalse();
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void aDirectRequestToTheBootErrorEndpointLeaksNothing() throws Exception {
        MockHttpServletResponse response = send(get("/error").accept("application/json"));

        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // Reached directly (no container error attributes): a generic server error, never a trace or a message.
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body).doesNotContain("trace", "exception", "message", "at ar.cetobhb");
    }
}
