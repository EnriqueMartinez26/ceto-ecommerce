package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The real {@code SpringApplication} with the real application.yml (review fix batch 9, item 3): the categories that
 * keep buyer data out of the log ({@link PiiLogLevelGuard#SHIPPED_LEVELS}) must stay at their shipped level. Overrides
 * come from command-line arguments, environment variables and logging groups, in any profile. A PARENT category set
 * to TRACE does not lower a silenced child (Logback gives a logger its own explicit level first); that is asserted on
 * the effective level of the real loggers, not assumed.
 */
class PiiLogLevelGuardStartupTest {

    @Configuration(proxyBeanMethods = false)
    @Import(PiiLogLevelGuard.class)
    static class App {
    }

    @AfterEach
    void restoreLoggingLevels() {
        LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
        for (String parent : new String[] {"org.springframework", "org.springframework.web", "org.hibernate",
                "org.hibernate.orm", "org.hibernate.orm.jdbc", "ar.cetobhb"}) {
            system.setLogLevel(parent, null);
        }
        system.setLogLevel("ROOT", LogLevel.INFO);
    }

    private static ConfigurableApplicationContext run(String... arguments) {
        return start(false, Map.of(), new String[0], arguments);
    }

    private static ConfigurableApplicationContext start(boolean lazy, Map<String, Object> variables, String[] profiles,
            String... arguments) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).lazyInitialization(lazy).profiles(profiles);
        if (!variables.isEmpty()) {
            builder.initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("test-systemEnvironment", variables)));
        }
        return builder.run(arguments);
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    private static void assertRefused(Throwable failure, String message) {
        assertThat(failure).isNotNull();
        assertThat(messagesOf(failure)).contains(message);
    }

    static Stream<Arguments> categories() {
        return PiiLogLevelGuard.SHIPPED_LEVELS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static String environmentName(String category) {
        return "LOGGING_LEVEL_" + category.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    @Test
    void theShippedApplicationYmlStartsAndSetsEveryCategoryOfTheListAtItsShippedLevel() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
            PiiLogLevelGuard.SHIPPED_LEVELS.forEach((category, level) ->
                    assertThat(context.getEnvironment().getProperty("logging.level." + category))
                            .as(category).isEqualTo(level));
        }
    }

    @ParameterizedTest
    @MethodSource("categories")
    void everyCategoryOfTheListIsEffectivelySilencedByTheShippedYml(String category, String level) {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.isActive()).isTrue();
            org.slf4j.Logger logger = LoggerFactory.getLogger(category);
            assertThat(logger.isDebugEnabled()).as(category + " debug").isFalse();
            assertThat(logger.isTraceEnabled()).as(category + " trace").isFalse();
            assertThat(logger.isInfoEnabled()).as(category + " info").isEqualTo("INFO".equals(level));
        }
    }

    @ParameterizedTest
    @MethodSource("categories")
    void aCommandLineLevelBelowTheShippedOneStopsTheStartup(String category, String level) {
        Throwable failure = catchThrowable(() -> run("--logging.level." + category + "=TRACE").close());

        assertRefused(failure, "logging.level." + category + " must be " + level + " or higher");
    }

    @ParameterizedTest
    @MethodSource("categories")
    void anEnvironmentVariableBelowTheShippedLevelStopsTheStartup(String category, String level) {
        Throwable failure = catchThrowable(() -> start(false, Map.of(environmentName(category), "DEBUG"),
                new String[0]).close());

        assertRefused(failure, "logging.level." + category + " must be " + level + " or higher");
    }

    @Test
    void aBlankLevelCountsAsRemovedAndStopsTheStartup() {
        Throwable failure = catchThrowable(() -> run("--logging.level.org.hibernate.SQL=").close());

        assertRefused(failure, "logging.level.org.hibernate.SQL must be OFF or higher");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INFO", "WARN", "ERROR", "OFF"})
    void theDispatcherServletMayBeRaisedToItsShippedLevelOrAbove(String level) {
        try (ConfigurableApplicationContext context =
                run("--logging.level.org.springframework.web.servlet.DispatcherServlet=" + level)) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "TRACE"})
    void theDispatcherServletBelowInfoStopsTheStartup(String level) {
        Throwable failure = catchThrowable(
                () -> run("--logging.level.org.springframework.web.servlet.DispatcherServlet=" + level).close());

        assertRefused(failure, "logging.level.org.springframework.web.servlet.DispatcherServlet must be INFO or higher");
    }

    @Test
    void aSubCategoryOfASilencedCategoryBelowItsLevelStopsTheStartupFromTheCommandLineAndTheEnvironment() {
        Throwable fromArgument = catchThrowable(
                () -> run("--logging.level.org.hibernate.orm.jdbc.bind.extra=TRACE").close());
        Throwable fromEnvironment = catchThrowable(() -> start(false,
                Map.of("LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_BIND_EXTRA", "DEBUG"), new String[0]).close());

        assertRefused(fromArgument, PiiLogLevelGuard.SUB_CATEGORY_MESSAGE);
        assertRefused(fromEnvironment, PiiLogLevelGuard.SUB_CATEGORY_MESSAGE);
    }

    @Test
    void aSubCategoryAtOffStartsBecauseItIsNotBelowTheShippedLevel() {
        try (ConfigurableApplicationContext context = run("--logging.level.org.hibernate.orm.jdbc.bind.extra=OFF")) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
        }
    }

    @Test
    void aLoggingGroupThatListsASilencedCategoryAtDebugStopsTheStartup() {
        Throwable failure = catchThrowable(() -> run(
                "--logging.group.sqlish=org.hibernate.SQL,org.hibernate.orm.jdbc.bind",
                "--logging.level.sqlish=DEBUG").close());

        assertRefused(failure, PiiLogLevelGuard.GROUP_MESSAGE);
    }

    @Test
    void aLoggingGroupThatListsOnlyAParentCategoryIsNotRefusedAndLeavesTheChildSilenced() {
        try (ConfigurableApplicationContext context = run("--logging.group.wide=org.hibernate,org.springframework.web",
                "--logging.level.wide=TRACE")) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
            assertThat(LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind").isTraceEnabled()).isFalse();
            assertThat(LoggerFactory.getLogger("org.hibernate.SQL").isDebugEnabled()).isFalse();
            assertThat(LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation."
                    + "RequestResponseBodyMethodProcessor").isTraceEnabled()).isFalse();
        }
    }

    /**
     * Logback: a logger with an explicit level ignores the level of its ancestors. The guard relies on it, so a parent
     * at TRACE must start AND must leave every silenced child where the yml put it.
     */
    @Test
    void aParentCategorySetToTraceStartsAndDoesNotOverrideAnyExplicitChild() {
        try (ConfigurableApplicationContext context = run("--logging.level.root=TRACE",
                "--logging.level.org.springframework=TRACE", "--logging.level.org.springframework.web=TRACE",
                "--logging.level.org.hibernate=TRACE", "--logging.level.org.hibernate.orm.jdbc=TRACE",
                "--logging.level.ar.cetobhb=TRACE")) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
            assertThat(LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation.Other")
                    .isTraceEnabled()).as("positive control: the parent really is at TRACE").isTrue();
            PiiLogLevelGuard.SHIPPED_LEVELS.forEach((category, level) -> {
                org.slf4j.Logger logger = LoggerFactory.getLogger(category);
                assertThat(logger.isTraceEnabled()).as(category + " trace").isFalse();
                assertThat(logger.isDebugEnabled()).as(category + " debug").isFalse();
            });
        }
    }

    @Test
    void theBootDebugFlagDoesNotRaiseTheSilencedCategoriesBecauseTheExplicitLevelWinsOverIt() {
        try (ConfigurableApplicationContext context = run("--debug=true")) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
            assertThat(LoggerFactory.getLogger("org.hibernate.SQL").isDebugEnabled()).isFalse();
            assertThat(LoggerFactory.getLogger("org.springframework.web.servlet.DispatcherServlet").isDebugEnabled())
                    .isFalse();
        }
    }

    @Test
    void theTomcatAccessLogIsRefusedFromTheCommandLineAndTheEnvironment() {
        Throwable fromArgument = catchThrowable(() -> run("--server.tomcat.accesslog.enabled=true").close());
        Throwable fromEnvironment = catchThrowable(() -> start(false,
                Map.of("SERVER_TOMCAT_ACCESSLOG_ENABLED", "true"), new String[0]).close());

        assertRefused(fromArgument, PiiLogLevelGuard.ACCESS_LOG_MESSAGE);
        assertRefused(fromEnvironment, PiiLogLevelGuard.ACCESS_LOG_MESSAGE);
    }

    @Test
    void anExplicitlyDisabledAccessLogStarts() {
        try (ConfigurableApplicationContext context = run("--server.tomcat.accesslog.enabled=false")) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
        }
    }

    @Test
    void theRequestDetailsFlagOfSpringMvcIsRefusedFromTheCommandLineAndTheEnvironment() {
        Throwable fromArgument = catchThrowable(() -> run("--spring.mvc.log-request-details=true").close());
        Throwable fromEnvironment = catchThrowable(() -> start(false,
                Map.of("SPRING_MVC_LOGREQUESTDETAILS", "true"), new String[0]).close());
        Throwable mixedCase = catchThrowable(() -> run("--spring.mvc.log-request-details=TrUe").close());

        assertRefused(fromArgument, PiiLogLevelGuard.REQUEST_DETAILS_MESSAGE);
        assertRefused(fromEnvironment, PiiLogLevelGuard.REQUEST_DETAILS_MESSAGE);
        assertRefused(mixedCase, PiiLogLevelGuard.REQUEST_DETAILS_MESSAGE);
        assertThat(messagesOf(mixedCase)).doesNotContain("TrUe");
    }

    @Test
    void anExplicitlyDisabledRequestDetailsFlagStartsAndTheShippedConfigurationDoesNotSetIt() {
        try (ConfigurableApplicationContext context = run("--spring.mvc.log-request-details=false")) {
            assertThat(context.getBean(PiiLogLevelGuard.class)).isNotNull();
        }
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getEnvironment().getProperty("spring.mvc.log-request-details")).isNull();
        }
    }

    /**
     * The argument and body loggers. In Spring 7 {@code InvocableHandlerMethod} and {@code ServletInvocableHandlerMethod}
     * log through the static logger of {@code HandlerMethod} ("Arguments: [...]" at TRACE, the return value on a handling
     * error), which is already in the list; they are named too, so a future version that logs under its own class stays
     * silenced. {@code org.springframework.web.HttpLogging} is the hidden fallback category of the HTTP message converters
     * (they log through it when their own category is not enabled). {@code RequestMappingHandlerAdapter} logs only the
     * names of the controller advice beans, so it is not listed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"org.springframework.web.method.support.InvocableHandlerMethod",
            "org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod",
            "org.springframework.web.HttpLogging", "org.springframework.web.method.HandlerMethod"})
    void theArgumentAndBodyLoggersAreInTheListAtOffAndEffectivelySilenced(String category) {
        assertThat(PiiLogLevelGuard.SHIPPED_LEVELS).containsEntry(category, "OFF");
        try (ConfigurableApplicationContext context = run("--logging.level.org.springframework.web=TRACE")) {
            assertThat(context.isActive()).isTrue();
            assertThat(LoggerFactory.getLogger(category).isTraceEnabled()).as(category).isFalse();
            assertThat(LoggerFactory.getLogger(category).isErrorEnabled()).as(category).isFalse();
        }
    }

    @Test
    void theGuardRunsInEveryProfile() {
        for (String profile : new String[] {"prod", "test", "dev", "default"}) {
            Throwable failure = catchThrowable(() -> start(false, Map.of(), new String[] {profile},
                    "--logging.level.org.hibernate.orm.jdbc.bind=TRACE").close());

            assertRefused(failure, "logging.level.org.hibernate.orm.jdbc.bind must be OFF or higher");
        }
    }

    @Test
    void withLazyInitializationOnTheGuardStillRunsAtStartup() {
        Throwable failure = catchThrowable(() -> start(true, Map.of(), new String[0],
                "--logging.level.org.hibernate.orm.jdbc.bind=TRACE").close());

        assertRefused(failure, "logging.level.org.hibernate.orm.jdbc.bind must be OFF or higher");
    }

    @Test
    void theFailureTextIsFixedAndNeverRepeatsTheValue() {
        Throwable failure = catchThrowable(() -> run("--logging.level.org.hibernate.SQL=dEbUg").close());
        Throwable access = catchThrowable(() -> run("--server.tomcat.accesslog.enabled=TrUe").close());

        assertThat(messagesOf(failure)).contains("must be OFF or higher").doesNotContain("dEbUg");
        assertThat(messagesOf(access)).contains(PiiLogLevelGuard.ACCESS_LOG_MESSAGE).doesNotContain("TrUe");
    }

    @Test
    void anEnvironmentWithoutTheListStopsTheStartupNamingTheFirstMissingCategory() {
        assertThatThrownBy(() -> new PiiLogLevelGuard(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be");
    }

    @Test
    void anEnvironmentWithTheWholeListAtItsShippedLevelIsAccepted() {
        MockEnvironment environment = new MockEnvironment();
        PiiLogLevelGuard.SHIPPED_LEVELS.forEach((category, level) ->
                environment.setProperty("logging.level." + category, level));

        assertThatCode(() -> new PiiLogLevelGuard(environment)).doesNotThrowAnyException();
    }
}
