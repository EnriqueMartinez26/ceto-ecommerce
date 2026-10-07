package ar.cetobhb.common.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Pure check behind the schema guard: the schema belongs to Flyway only (CLAUDE.md section 3).
 * Reads the effective values (environment variables and command-line overrides included, relaxed
 * binding applied) and rejects every channel through which Hibernate or JPA could generate DDL.
 */
final class SchemaManagementCheck {

    static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto";

    /** Optional channels: unset is fine, a set value must be allowed. */
    static final List<String> OPTIONAL_CHANNELS = List.of(
            "spring.jpa.properties.hibernate.hbm2ddl.auto",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
            "spring.jpa.properties.javax.persistence.schema-generation.database.action",
            "hibernate.hbm2ddl.auto",
            "jakarta.persistence.schema-generation.database.action",
            "javax.persistence.schema-generation.database.action");

    private static final Set<String> ALLOWED = Set.of("validate", "none");

    private SchemaManagementCheck() {
    }

    static void verify(Environment environment) {
        Binder binder = Binder.get(environment);
        String ddlAuto = binder.bind(DDL_AUTO, String.class).orElse(null);
        if (!isAllowed(ddlAuto)) {
            throw refusal(DDL_AUTO, ddlAuto, "must be 'validate' or 'none'");
        }
        for (String key : OPTIONAL_CHANNELS) {
            String value = binder.bind(key, String.class).orElse(null);
            if (value != null && !isAllowed(value)) {
                throw refusal(key, value, "must be unset, 'validate' or 'none'");
            }
        }
    }

    private static boolean isAllowed(String value) {
        return value != null && ALLOWED.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static IllegalStateException refusal(String key, String value, String rule) {
        return new IllegalStateException("Refusing to start: " + key + " " + rule
                + " (schema is Flyway-only, CLAUDE.md section 3), got: " + value);
    }
}
