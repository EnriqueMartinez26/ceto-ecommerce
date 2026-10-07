package ar.cetobhb;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractPostgresIT;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class ApplicationContextIT extends AbstractPostgresIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void contextBootsWithFlywayAndValidatedSchema() {
        assertThat(context.getBean(Clock.class)).isNotNull();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        Integer historyTables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'",
                Integer.class);
        assertThat(historyTables).isEqualTo(1);
    }

    @Test
    void effectiveHibernateConfigNeverGeneratesSchema() {
        Map<String, Object> properties = entityManagerFactory.getProperties();
        assertThat(properties.get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
        assertThat(properties).doesNotContainKey("jakarta.persistence.schema-generation.database.action");
        assertThat(properties).doesNotContainKey("javax.persistence.schema-generation.database.action");
    }

    @Test
    void runsOnPostgres16() {
        String version = jdbc.queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("16");
    }

    @Test
    void schedulingIsDisabledInTestProfile() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }
}
