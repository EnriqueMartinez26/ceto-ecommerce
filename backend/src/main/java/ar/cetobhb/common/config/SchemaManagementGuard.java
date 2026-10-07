package ar.cetobhb.common.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fail-closed guard: the schema belongs to Flyway only (CLAUDE.md section 3). Startup aborts unless
 * the effective {@code spring.jpa.hibernate.ddl-auto} is explicitly {@code validate} or {@code none}.
 * The effective value includes environment-variable and command-line overrides, and Hibernate's
 * native {@code hbm2ddl.auto} passthrough is checked too because it would override the Boot property.
 */
@Configuration(proxyBeanMethods = false)
public class SchemaManagementGuard {

    static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto";
    static final String NATIVE_HBM2DDL = "spring.jpa.properties.hibernate.hbm2ddl.auto";
    private static final Set<String> ALLOWED = Set.of("validate", "none");

    SchemaManagementGuard(Environment environment) {
        String effective = Binder.get(environment).bind(DDL_AUTO, String.class).orElse(null);
        if (!isAllowed(effective)) {
            throw new IllegalStateException(DDL_AUTO + " must be 'validate' or 'none' (schema is Flyway-only), got: "
                    + effective);
        }
        String nativeValue = environment.getProperty(NATIVE_HBM2DDL);
        if (nativeValue != null && !isAllowed(nativeValue)) {
            throw new IllegalStateException(NATIVE_HBM2DDL + " must not generate the schema, got: " + nativeValue);
        }
    }

    private static boolean isAllowed(String value) {
        return value != null && ALLOWED.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
