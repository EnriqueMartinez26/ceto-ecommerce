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

    /** Every MVC handler of the administration area (login and logout are Security filters, asserted by behaviour). */
    public static final Set<String> ADMIN_ROUTES = Set.of("GET /api/admin/session",
            "GET /api/admin/orders", "GET /api/admin/orders/{id}");

    /**
     * The administration routes an anonymous client may call, and only with the method listed: the session bootstrap.
     * {@code POST /api/admin/login} is anonymous-allowed too, but it is a Security filter route (no MVC handler), so it
     * is recorded here in words and asserted by behaviour in {@code AdminLoginServerIT}. Every other method on these
     * paths, and every other admin route, is 401 for an anonymous client.
     */
    public static final Set<String> ANONYMOUS_ADMIN_ROUTES = Set.of("GET /api/admin/session");

    public static final String ADMIN_PREFIX = "/api/admin/";

    /** What a mapping with no method answers to, in the order the generated test tries them. */
    private static final List<String> ANY_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /** Methods a CSRF filter does not check, so the generated call carries no token. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

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

    /** The routes outside the public set (the anonymous-allowed bootstrap included). */
    public static List<Mapped> nonPublicRoutes(ApplicationContext context) {
        return mvcRoutes(context).stream().filter(m -> !PUBLIC_ROUTES.contains(m.route())).toList();
    }

    /** The routes an anonymous client must never reach at all: non-public and not in {@link #ANONYMOUS_ADMIN_ROUTES}. */
    public static List<Mapped> protectedRoutes(ApplicationContext context) {
        return nonPublicRoutes(context).stream().filter(m -> !ANONYMOUS_ADMIN_ROUTES.contains(m.route())).toList();
    }

    /**
     * An anonymous call to a mapped route: path variables become {@code 1}, and a mutating method carries a VALID
     * CSRF token, so the only thing that can stop it is the missing login. A mapping with no method answers every
     * method, so it is tried with all the ones a client could use, HEAD and OPTIONS included. For an
     * anonymous-allowed route the calls are the methods it does NOT allow.
     */
    public static List<MockHttpServletRequestBuilder> anonymousCalls(Mapped route) {
        String path = concretePath(route);
        // An anonymous-allowed route keeps its one allowed method; every OTHER method on it must be a 401.
        List<String> methods = ANONYMOUS_ADMIN_ROUTES.contains(route.route())
                ? ANY_METHODS.stream().filter(method -> !method.equals(route.method())).toList()
                : route.method().equals("ANY") ? ANY_METHODS : List.of(route.method());
        List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
        for (String method : methods) {
            MockHttpServletRequestBuilder call = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (!SAFE_METHODS.contains(method)) {
                call = call.with(csrf());
            }
            calls.add(call);
        }
        return calls;
    }

    /**
     * The concrete path used for a route (what {@code instance} must equal in the 401 body): every path variable, with
     * or without a regex ({@code {id}}, {@code {id:\d{3}}}), becomes {@code 1}. A pattern with a wildcard ({@code *},
     * {@code **}, {@code ?}, {@code {*rest}}), an empty or unclosed variable cannot be turned into one path, so it
     * fails loudly instead of producing a path that would make the generated test check nothing.
     */
    public static String concretePath(Mapped route) {
        String pattern = route.pattern();
        StringBuilder path = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                throw new AssertionError("Unsupported wildcard in the route pattern: " + pattern);
            }
            if (c != '{') {
                path.append(c);
                i++;
                continue;
            }
            int depth = 0;
            int end = -1;
            for (int j = i; j < pattern.length(); j++) {
                if (pattern.charAt(j) == '{') {
                    depth++;
                } else if (pattern.charAt(j) == '}' && --depth == 0) {
                    end = j;
                    break;
                }
            }
            String variable = end < 0 ? "" : pattern.substring(i + 1, end);
            if (end < 0 || variable.isEmpty() || variable.startsWith("*") || variable.startsWith(":")) {
                throw new AssertionError("Unsupported or malformed path variable in the route pattern: " + pattern);
            }
            path.append('1');
            i = end + 1;
        }
        return path.toString();
    }
}
