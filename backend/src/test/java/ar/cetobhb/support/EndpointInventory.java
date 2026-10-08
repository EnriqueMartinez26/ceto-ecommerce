package ar.cetobhb.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * The endpoint inventory (REQ-HRD-03, REQ-HRD-08) as data, shared by the test that fixes it for the real application
 * and by the drift fixture that proves it notices a route nobody listed. Two explicit sets: the public routes and the
 * administration routes (Spring MVC handlers only: login and logout are Security filters, asserted by behaviour).
 * The error endpoint of Boot is allowlisted by handler type elsewhere and is not part of either set.
 */
public final class EndpointInventory {

    /** The three public API routes. */
    public static final Set<String> PUBLIC_ROUTES = Set.of(
            "GET /api/store/products", "GET /api/store/products/{id}", "POST /api/checkout/orders");

    /** Every MVC handler of the administration area. Empty until the first admin controller exists. */
    public static final Set<String> ADMIN_ROUTES = Set.of();

    public static final String ADMIN_PREFIX = "/api/admin/";

    private EndpointInventory() {
    }

    /** One mapped handler method and path pattern; {@code method} is {@code ANY} when the mapping has no method. */
    public record Mapped(String method, String pattern, Class<?> handler) {
        public String route() {
            return method + " " + pattern;
        }
    }

    /** Every MVC route of the context, without the error endpoint of Boot. */
    public static List<Mapped> mvcRoutes(ApplicationContext context) {
        List<Mapped> mapped = new ArrayList<>();
        for (RequestMappingInfoHandlerMapping mapping
                : context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                if (entry.getValue().getBeanType() == BasicErrorController.class) {
                    continue;
                }
                RequestMappingInfo info = entry.getKey();
                Set<String> paths = info.getPathPatternsCondition() == null
                        ? Set.of("?") : info.getPathPatternsCondition().getPatternValues();
                Set<String> methods = info.getMethodsCondition().getMethods().isEmpty()
                        ? Set.of("ANY")
                        : info.getMethodsCondition().getMethods().stream().map(Enum::name)
                                .collect(java.util.stream.Collectors.toSet());
                for (String method : methods) {
                    for (String path : paths) {
                        mapped.add(new Mapped(method, path, entry.getValue().getBeanType()));
                    }
                }
            }
        }
        return mapped;
    }

    /** Mapped routes that are in neither the public nor the administration allowlist: the build must fail on any. */
    public static Set<String> unexpected(ApplicationContext context) {
        Set<String> unexpected = new TreeSet<>();
        for (Mapped route : mvcRoutes(context)) {
            if (!PUBLIC_ROUTES.contains(route.route()) && !ADMIN_ROUTES.contains(route.route())) {
                unexpected.add(route.route());
            }
        }
        return unexpected;
    }

    /** The routes outside the public set: what must answer 401 to an anonymous client. */
    public static List<Mapped> nonPublicRoutes(ApplicationContext context) {
        return mvcRoutes(context).stream().filter(m -> !PUBLIC_ROUTES.contains(m.route())).toList();
    }

    /**
     * An anonymous call to a mapped route: path variables become {@code 1}, and a mutating method carries a VALID
     * CSRF token, so the only thing that can stop it is the missing login. A mapping with no method is tried with the
     * methods a client could use.
     */
    public static List<MockHttpServletRequestBuilder> anonymousCalls(Mapped route) {
        String path = concretePath(route);
        List<String> methods = route.method().equals("ANY") ? List.of("GET", "POST", "PUT", "DELETE")
                : List.of(route.method());
        List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
        for (String method : methods) {
            MockHttpServletRequestBuilder call = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (!method.equals("GET") && !method.equals("HEAD")) {
                call = call.with(csrf());
            }
            calls.add(call);
        }
        return calls;
    }

    /** The concrete path used for a route (what {@code instance} must equal in the 401 body). */
    public static String concretePath(Mapped route) {
        return route.pattern().replaceAll("\\{[^/}]+}", "1");
    }
}
