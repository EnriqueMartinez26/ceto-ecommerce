package ar.cetobhb.common.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Fail-closed check of the log levels that keep personal data out of the log, in every profile (review fix batch 9).
 * Request lines, rejected request values, request and response bodies, bound SQL parameters and the row of a constraint
 * violation all carry buyer data (name, phone, address) when their category is verbose. The shipped
 * {@code application.yml} silences each of them on its OWN category ({@link #SHIPPED_LEVELS}, the single list); the
 * application refuses to start when the effective configuration:
 * <ul>
 *   <li>does not set one of those categories, or sets it below its shipped level (command-line argument, environment
 *       variable or yml, all read with Boot's own relaxed {@link Binder});</li>
 *   <li>sets below the shipped level any entry UNDER one of those categories (for example
 *       {@code org.hibernate.orm.jdbc.bind.x});</li>
 *   <li>sets below the shipped level a logging group ({@code logging.group.*}) that lists one of those categories;</li>
 *   <li>turns the Tomcat access log on ({@code server.tomcat.accesslog.enabled=true}): its default pattern prints the
 *       request line with the query string, for example {@code ?q=<phone>}. Accepted limit: if an access log is ever
 *       needed, add a pattern without the query string and relax this check in the same change.</li>
 *   <li>turns on {@code spring.mvc.log-request-details}: the dispatcher would then log the request parameters and
 *       headers at DEBUG.</li>
 * </ul>
 * A PARENT category set more verbose does not need a check: Logback gives a logger its own explicit level before the one
 * it inherits, so {@code org.springframework.web=TRACE}, {@code org.hibernate=TRACE} or the root at TRACE leave a
 * silenced child where the yml put it (pinned by {@code PiiLogLevelGuardStartupTest}, and at runtime by the PII server
 * ITs). Boot's {@code --debug} flag is the same case. Security categories keep their own guard
 * ({@code SecurityLogLevelGuard}).
 *
 * <p>Limits, on purpose: it checks the CONFIGURATION at startup, not a level changed later through the logging system
 * (the PII tests do that to prove their control works). No actuator ({@code /loggers}) exists, so no HTTP route can
 * change a level at runtime. A {@code logback.xml} or other logging-system-native file is not read: the project ships
 * none. The failure text is fixed and never repeats a configured value.
 *
 * <p>{@link Lazy @Lazy(false)}: under {@code spring.main.lazy-initialization=true} Boot would otherwise defer this bean
 * and the check would never run.
 */
@Configuration(proxyBeanMethods = false)
@Lazy(false)
public class PiiLogLevelGuard {

    /** Boot's level names, from the most verbose to the quietest. */
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF");

    /**
     * The categories that must stay silenced and the level each one ships at (the value in application.yml). A category
     * is "at or above" its shipped level when its rank in {@link #LEVELS} is not lower.
     */
    public static final Map<String, String> SHIPPED_LEVELS = shippedLevels();

    static final String ACCESS_LOG_PROPERTY = "server.tomcat.accesslog.enabled";
    static final String ACCESS_LOG_MESSAGE =
            ACCESS_LOG_PROPERTY + " must be false: the access log prints the query string of a request";
    static final String REQUEST_DETAILS_PROPERTY = "spring.mvc.log-request-details";
    static final String REQUEST_DETAILS_MESSAGE =
            REQUEST_DETAILS_PROPERTY + " must be false: it makes the dispatcher log request parameters and headers";
    static final String SUB_CATEGORY_MESSAGE =
            "A logging.level entry under a category that keeps personal data out of the log is below its shipped level";
    static final String GROUP_MESSAGE =
            "A logging group that contains a category that keeps personal data out of the log is below its shipped level";

    private static Map<String, String> shippedLevels() {
        Map<String, String> levels = new LinkedHashMap<>();
        // Hibernate 7: the row of a CHECK violation, the bound parameters and the extracted values.
        levels.put("org.hibernate.orm.jdbc.error", "OFF");
        levels.put("org.hibernate.orm.jdbc.warn", "OFF");
        levels.put("org.hibernate.orm.jdbc.bind", "OFF");
        levels.put("org.hibernate.orm.jdbc.extract", "OFF");
        levels.put("org.hibernate.SQL", "OFF");
        // Hibernate 7 lists every managed entity with all its values on flush at DEBUG ("Listing entities:").
        levels.put("org.hibernate.orm.core", "INFO");
        // Spring MVC: rejected request values and the request line with its query string.
        levels.put("org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver", "OFF");
        levels.put("org.springframework.web.servlet.PageNotFound", "OFF");
        levels.put("org.springframework.web.servlet.DispatcherServlet", "INFO");
        levels.put("org.springframework.web.method.HandlerMethod", "OFF");
        // The argument and return value loggers of a handler call (static logger of HandlerMethod in Spring 7; named too).
        levels.put("org.springframework.web.method.support.InvocableHandlerMethod", "OFF");
        levels.put("org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod", "OFF");
        // The hidden fallback category of the HTTP message converters (HttpLogging.forLog).
        levels.put("org.springframework.web.HttpLogging", "OFF");
        levels.put("org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver", "OFF");
        // Spring MVC: the request and response bodies ("Read ... to [...]", "Writing [...]").
        levels.put("org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodProcessor",
                "OFF");
        levels.put("org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodArgumentResolver",
                "OFF");
        levels.put("org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor", "OFF");
        levels.put("org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor", "OFF");
        return Map.copyOf(levels);
    }

    PiiLogLevelGuard(Environment environment) {
        Binder binder = Binder.get(environment);
        Map<String, String> levels = binder.bind("logging.level", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        Map<String, List<String>> groups = bindGroups(binder);

        Map<String, String> configured = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : levels.entrySet()) {
            configured.put(lower(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<String, String> shipped : SHIPPED_LEVELS.entrySet()) {
            String category = lower(shipped.getKey());
            if (!configured.containsKey(category) || !atOrAbove(configured.get(category), shipped.getValue())) {
                throw new IllegalStateException("logging.level." + shipped.getKey() + " must be "
                        + shipped.getValue() + " or higher");
            }
            for (Map.Entry<String, String> entry : configured.entrySet()) {
                if (entry.getKey().startsWith(category + ".") && !atOrAbove(entry.getValue(), shipped.getValue())) {
                    throw new IllegalStateException(SUB_CATEGORY_MESSAGE);
                }
            }
            for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                String groupLevel = configured.get(lower(group.getKey()));
                if (groupLevel != null && holds(group.getValue(), category)
                        && !atOrAbove(groupLevel, shipped.getValue())) {
                    throw new IllegalStateException(GROUP_MESSAGE);
                }
            }
        }
        if (Boolean.TRUE.equals(binder.bind(REQUEST_DETAILS_PROPERTY, Boolean.class).orElse(false))) {
            throw new IllegalStateException(REQUEST_DETAILS_MESSAGE);
        }
        if (Boolean.TRUE.equals(binder.bind(ACCESS_LOG_PROPERTY, Boolean.class).orElse(false))) {
            throw new IllegalStateException(ACCESS_LOG_MESSAGE);
        }
    }

    /** The logging groups, bound as Boot binds them for the logging system. */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> bindGroups(Binder binder) {
        ResolvableType groupType = ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class),
                ResolvableType.forClassWithGenerics(List.class, String.class));
        return (Map<String, List<String>>) binder.bind("logging.group", Bindable.of(groupType)).orElse(Map.of());
    }

    /** True when a group lists the category itself or a category under it (a parent in a group does not lower it). */
    private static boolean holds(List<String> members, String category) {
        for (String member : members) {
            String name = lower(member);
            if (name.equals(category) || name.startsWith(category + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean atOrAbove(String level, String minimum) {
        int rank = LEVELS.indexOf(level == null ? "" : level.trim().toUpperCase(Locale.ROOT));
        return rank >= 0 && rank >= LEVELS.indexOf(minimum);
    }

    private static String lower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
