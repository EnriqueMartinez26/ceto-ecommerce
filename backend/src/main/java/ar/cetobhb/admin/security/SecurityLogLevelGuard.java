package ar.cetobhb.admin.security;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Fail-closed check of the Spring Security log level, in every profile (D-20261008-46, REQ-AUTH-12). At DEBUG Spring
 * Security prints the authenticated account, the client address and the session id, and the DAO provider prints the
 * username that was typed. The application therefore refuses to start when the effective configuration:
 * <ul>
 *   <li>does not set {@code logging.level.org.springframework.security} on the category itself, or sets it below WARN
 *       (DEBUG, TRACE, INFO or anything that is not WARN, ERROR, FATAL or OFF);</li>
 *   <li>sets any sub-category of it (a {@code logging.level} entry under {@code org.springframework.security.}) below
 *       WARN;</li>
 *   <li>does not keep {@code org.springframework.security.authentication.dao} at OFF;</li>
 *   <li>sets below WARN a logging group ({@code logging.group.*}) that contains one of those categories.</li>
 * </ul>
 * The values are read with Boot's own {@link Binder}, the same relaxed binding the logging system uses, so a command-line
 * argument, an environment variable ({@code LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG}) and the yml are all seen
 * as the logging system sees them. The failure text is fixed and never repeats a value.
 *
 * <p>Limits, on purpose: it checks the CONFIGURATION at startup, not the level of a logger that someone changes later
 * through the logging system (the PII tests do that to prove their control works). There is no actuator
 * ({@code /loggers}) in this application, so no HTTP route can change a level at runtime; the guard would not see one.
 * A {@code logback.xml} or other logging-system-native file is not read either: the project ships none.
 *
 * <p>{@link Lazy @Lazy(false)}: under {@code spring.main.lazy-initialization=true} Boot would otherwise defer this bean
 * and the check would never run.
 */
@Configuration(proxyBeanMethods = false)
@Lazy(false)
class SecurityLogLevelGuard {

    static final String ROOT_CATEGORY = "org.springframework.security";
    static final String DAO_CATEGORY = "org.springframework.security.authentication.dao";

    /** Levels at WARN or above, upper case (Boot's {@code LogLevel} names). */
    private static final Set<String> WARN_OR_ABOVE = Set.of("WARN", "ERROR", "FATAL", "OFF");

    private static final String LEVEL_PROPERTY = "logging.level";
    private static final String GROUP_PROPERTY = "logging.group";

    SecurityLogLevelGuard(Environment environment) {
        Binder binder = Binder.get(environment);
        Map<String, String> levels = binder.bind(LEVEL_PROPERTY, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        Map<String, List<String>> groups = bindGroups(binder);
        boolean rootSet = false;
        boolean daoSet = false;
        for (Map.Entry<String, String> entry : levels.entrySet()) {
            String category = lower(entry.getKey());
            String level = entry.getValue();
            if (category.equals(DAO_CATEGORY)) {
                daoSet = true;
                if (!"OFF".equals(upper(level))) {
                    throw new IllegalStateException(DAO_MESSAGE);
                }
            } else if (category.equals(ROOT_CATEGORY)) {
                rootSet = true;
                if (!isWarnOrAbove(level)) {
                    throw new IllegalStateException(ROOT_MESSAGE);
                }
            } else if (category.startsWith(ROOT_CATEGORY + ".")) {
                if (!isWarnOrAbove(level)) {
                    throw new IllegalStateException(SUB_CATEGORY_MESSAGE);
                }
            } else if (groupHoldsSecurity(groups, entry.getKey()) && !isWarnOrAbove(level)) {
                throw new IllegalStateException(GROUP_MESSAGE);
            }
        }
        if (!rootSet) {
            throw new IllegalStateException(ROOT_MESSAGE);
        }
        if (!daoSet) {
            throw new IllegalStateException(DAO_MESSAGE);
        }
    }

    private static final String ROOT_MESSAGE = "logging.level.org.springframework.security must be WARN or higher";
    private static final String DAO_MESSAGE =
            "logging.level.org.springframework.security.authentication.dao must be OFF";
    private static final String SUB_CATEGORY_MESSAGE =
            "A logging.level entry under org.springframework.security is below WARN";
    private static final String GROUP_MESSAGE =
            "A logging group that contains org.springframework.security is below WARN";

    /** The logging groups, bound as Boot binds them for the logging system. */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> bindGroups(Binder binder) {
        ResolvableType groupType = ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class),
                ResolvableType.forClassWithGenerics(List.class, String.class));
        return (Map<String, List<String>>) binder.bind(GROUP_PROPERTY, Bindable.of(groupType)).orElse(Map.of());
    }

    private static boolean groupHoldsSecurity(Map<String, List<String>> groups, String name) {
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            if (group.getKey().trim().equalsIgnoreCase(name.trim())) {
                for (String member : group.getValue()) {
                    String category = lower(member);
                    if (category.equals(ROOT_CATEGORY) || category.startsWith(ROOT_CATEGORY + ".")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isWarnOrAbove(String level) {
        return WARN_OR_ABOVE.contains(upper(level));
    }

    private static String lower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static String upper(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
    }
}
