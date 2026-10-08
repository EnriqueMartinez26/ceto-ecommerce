package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import ar.cetobhb.support.AbstractHttpIT;
import jakarta.validation.Valid;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
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

    private static final String APP_PACKAGE = "ar.cetobhb.";
    private static final String BV = "jakarta.validation.constraints.";
    private static final String HV_RANGE = "org.hibernate.validator.constraints.Range";
    private static final Set<String> LOWER_BOUNDS = Set.of(BV + "Min", BV + "DecimalMin", BV + "Positive",
            BV + "PositiveOrZero", HV_RANGE);
    private static final Set<String> UPPER_BOUNDS = Set.of(BV + "Max", BV + "DecimalMax", BV + "Negative",
            BV + "NegativeOrZero", HV_RANGE);
    private static final Set<String> LENGTH_BOUNDS = Set.of(BV + "Size", "org.hibernate.validator.constraints.Length");

    /**
     * The Spring-side mirror of the ArchUnit rule REQUEST_INPUTS_ARE_BOUNDED (REQ-HRD-05): it starts from the handler
     * methods Spring really registered, so interface-declared mappings and type-level {@code @RequestMapping} without a
     * stereotype are covered too. Every numeric parameter needs a minimum and a maximum, every string, collection,
     * map or array a {@code @Size(max)}, every body (and every application-type model object) {@code @Valid}, and
     * the same holds for each component of those types, walked through nested types and collections. Known gap:
     * the elements of a collection ({@code List<@Size(max = 10) String>}) are not checked; today no collection of
     * simple values is accepted from a client.
     */
    @Test
    void everyHandlerParameterAndBodyComponentIsBoundedAndValidated() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
                for (MethodParameter declared : handler.getMethodParameters()) {
                    MethodParameter parameter = declared.nestedIfOptional();
                    String where = handler.getShortLogMessage() + " parameter " + declared.getParameterIndex();
                    Class<?> type = parameter.getNestedParameterType();
                    Annotation[] annotations = declared.getParameterAnnotations();
                    boolean body = declared.hasParameterAnnotation(RequestBody.class);
                    if (body) {
                        requireValid(annotations, where + " (body)", problems);
                    }
                    if (isInput(type)) {
                        unbounded(type, annotations, where, problems);
                        checked++;
                    } else if (isApplicationType(type)) {
                        if (!body) {
                            requireValid(annotations, where + " (model object)", problems);
                        }
                        checked++;
                    }
                    if (body || isApplicationType(type)) {
                        walk(parameter.getNestedGenericParameterType(), where, new HashSet<>(), problems);
                    }
                }
            }
        }

        assertThat(checked).as("inputs inspected").isGreaterThanOrEqualTo(3);
        assertThat(problems).isEmpty();
    }

    private static void walk(Type type, String path, Set<Class<?>> seen, List<String> problems) {
        for (Class<?> dto : applicationTypes(type)) {
            if (!seen.add(dto)) {
                continue;
            }
            for (Field field : dto.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String where = path + " -> " + dto.getSimpleName() + "." + field.getName();
                Class<?> raw = field.getType();
                if (Optional.class.isAssignableFrom(raw)) {
                    raw = applicationOrSimple(field.getGenericType());
                }
                if (isInput(raw)) {
                    unbounded(raw, field.getAnnotations(), where, problems);
                }
                if (!applicationTypes(field.getGenericType()).isEmpty()) {
                    requireValid(field.getAnnotations(), where, problems);
                    walk(field.getGenericType(), where, seen, problems);
                }
            }
        }
    }

    private static Class<?> applicationOrSimple(Type optional) {
        return optional instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> c
                ? c : Object.class;
    }

    /** Application classes (not enums) that a type is made of, looking inside generics, wildcards and arrays. */
    private static Set<Class<?>> applicationTypes(Type type) {
        Set<Class<?>> found = new HashSet<>();
        collect(type, found);
        found.removeIf(c -> !isApplicationType(c));
        return found;
    }

    private static void collect(Type type, Set<Class<?>> out) {
        if (type instanceof Class<?> c) {
            out.add(c.isArray() ? c.getComponentType() : c);
        } else if (type instanceof ParameterizedType p) {
            collect(p.getRawType(), out);
            Arrays.stream(p.getActualTypeArguments()).forEach(argument -> collect(argument, out));
        } else if (type instanceof WildcardType w) {
            Arrays.stream(w.getUpperBounds()).forEach(bound -> collect(bound, out));
        } else if (type instanceof GenericArrayType a) {
            collect(a.getGenericComponentType(), out);
        }
    }

    private static boolean isApplicationType(Class<?> type) {
        return type.getName().startsWith(APP_PACKAGE) && !type.isEnum() && !type.isInterface();
    }

    private static boolean isInput(Class<?> type) {
        return type.isArray() || CharSequence.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type) || isNumeric(type);
    }

    private static boolean isNumeric(Class<?> type) {
        return type.isPrimitive() && type != boolean.class && type != char.class
                || Number.class.isAssignableFrom(type);
    }

    private static void requireValid(Annotation[] annotations, String where, List<String> problems) {
        boolean valid = Arrays.stream(annotations)
                .anyMatch(a -> a.annotationType() == Valid.class || a.annotationType() == Validated.class);
        if (!valid) {
            problems.add(where + " lacks @Valid");
        }
    }

    private static void unbounded(Class<?> type, Annotation[] annotations, String where, List<String> problems) {
        if (isNumeric(type)) {
            boolean lower = Arrays.stream(annotations).anyMatch(a -> LOWER_BOUNDS.contains(name(a)));
            boolean upper = Arrays.stream(annotations).anyMatch(a -> UPPER_BOUNDS.contains(name(a)));
            if (!lower || !upper) {
                problems.add(where + " is numeric and lacks " + (lower ? "a maximum" : upper ? "a minimum"
                        : "a minimum and a maximum"));
            }
            return;
        }
        boolean sized = Arrays.stream(annotations).anyMatch(a -> LENGTH_BOUNDS.contains(name(a)) && maxOf(a) < Integer.MAX_VALUE);
        if (!sized) {
            problems.add(where + " is a string, collection or array and lacks @Size(max = ...)");
        }
    }

    private static String name(Annotation annotation) {
        return annotation.annotationType().getName();
    }

    private static int maxOf(Annotation annotation) {
        try {
            return (int) annotation.annotationType().getMethod("max").invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/swagger-ui.html", "/v3/api-docs",
            "/api/store/products/1/anything", "/index.html", "/favicon.ico", "/"})
    void unknownPathsAreTheStandard404(String path) throws Exception {
        MockHttpServletResponse response = send(get(path));

        assertThat(response.getStatus()).isEqualTo(404);
        JsonNode body = JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(body.has("type")).isFalse();
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    /**
     * Nothing is mapped under {@code /api/admin/} yet, and the Security filter stops every request there before the
     * handler lookup: an unmapped admin path is a bare 401 for an anonymous client, not the 404 of the public side
     * (slice X1; the generated per-route inventory arrives with the chain, slice X3).
     */
    @Test
    void anUnmappedAdminPathIsStoppedByTheSecurityFilterWith401() throws Exception {
        MockHttpServletResponse response = send(get("/api/admin/x"));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    /** A direct request is not an error dispatch: /error answers the standard 404, whatever the method or Accept. */
    @ParameterizedTest
    @CsvSource({"GET,application/json", "GET,text/html", "POST,application/json", "PUT,*/*", "DELETE,*/*",
            "PATCH,*/*", "OPTIONS,*/*"})
    void aDirectRequestToTheBootErrorEndpointIsTheStandard404(String method, String accept) throws Exception {
        MockHttpServletResponse response = send(request(HttpMethod.valueOf(method), "/error").accept(accept));

        assertThat(response.getStatus()).isEqualTo(404);
        JsonNode body = JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(body.has("trace")).isFalse();
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }
}
