package ar.cetobhb.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves each rule bites: it must fail on a deliberately violating fixture and pass on a compliant one.
 * Fixtures live in test sources only; {@link ArchitectureTest} never sees them (DoNotIncludeTests).
 * They sit under the {@code archfixtures} root, deliberately OUTSIDE the application base package
 * ({@code ar.cetobhb}): Spring Boot scans that package for components and entities, so a fixture
 * placed there could be picked up by the integration tests. The {@code controller/service/repository/domain}
 * package names are kept so the {@code ..x..} rule patterns still match.
 */
class ArchitectureRulesTest {

    private static final String FIXTURES = "archfixtures.ar.cetobhb.";

    private static JavaClasses fixtures(String subPackage) {
        return new ClassFileImporter().importPackages(FIXTURES + subPackage);
    }

    @Test
    void domainPurityRuleFailsWhenDomainDependsOnJpa() {
        assertThatThrownBy(() -> ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE.check(fixtures("domainimpure")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ImpureDomain")
                .hasMessageContaining("jakarta.persistence");
    }

    @Test
    void layeringRuleFailsWhenControllerReachesRepository() {
        assertThatThrownBy(() -> ArchitectureRules.LAYERS.check(fixtures("layering")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BadController")
                .hasMessageContaining("BadRepository");
    }

    @Test
    void layeringRuleFailsWhenNoLayerHasAnyClassSoARenameCannotEmptyIt() {
        assertThatThrownBy(() -> ArchitectureRules.LAYERS.check(fixtures("clean.domain")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Layer '")
                .hasMessageContaining("' is empty");
    }

    @Test
    void layeringRuleAcceptsAControllerThatOnlyCallsItsService() {
        assertThatCode(() -> ArchitectureRules.LAYERS.check(fixtures("clean.catalog"))).doesNotThrowAnyException();
    }

    @Test
    void transactionalRuleFailsOnClassLevelAnnotationOutsideServices() {
        assertThatThrownBy(() -> ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES.check(fixtures("txclass")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TxClassOnly");
    }

    @Test
    void transactionalRuleFailsOnMethodLevelAnnotationOutsideServices() {
        assertThatThrownBy(() -> ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES.check(fixtures("txmethod")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TxMethodOnly")
                .hasMessageContaining("boundaryMethod");
    }

    @Test
    void transactionalRuleFailsOnRepositoryMethodsThatAreNotMandatory() {
        assertThatThrownBy(() -> ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES.check(fixtures("txrepo")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NonMandatoryRepository")
                .hasMessageContaining("defaultPropagation")
                .hasMessageContaining("requiresNew");
    }

    @Test
    void transactionalRuleAllowsMandatoryOnRepositoryMethods() {
        assertThatCode(() -> ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES.check(fixtures("txmandatory")))
                .doesNotThrowAnyException();
    }

    @Test
    void transactionalRuleStillFailsOnMandatoryOutsideRepositories() {
        assertThatThrownBy(() -> ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES.check(fixtures("txmandatoryweb")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MandatoryOutsideRepository")
                .hasMessageContaining("mandatoryInTheWrongLayer");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "instantNow", "localDateTimeNow", "localDateNow", "localDateNowWithZone",
            "localTimeNow", "zonedDateTimeNow", "offsetDateTimeNow", "yearNow", "yearMonthNow", "monthDayNow",
            "clockSystemUtc", "clockSystemWithZone", "clockSystemDefaultZone", "zoneIdSystemDefault",
            "systemCurrentTimeMillis", "newDate", "calendarGetInstance",
            "offsetDateTimeNowWithZoneOffset", "zonedDateTimeNowWithZone", "timeZoneGetDefault",
            "newGregorianCalendar", "newGregorianCalendarWithTimeZone", "newGregorianCalendarWithLocale",
            "newGregorianCalendarWithTimeZoneAndLocale"})
    void clockRuleFailsOnEachSystemClockAccess(String violatingMethod) {
        assertThatThrownBy(() -> ArchitectureRules.NO_SYSTEM_CLOCK.check(fixtures("clock")))
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .contains("ClockViolator." + violatingMethod + "("));
    }


    @Test
    void jakartaTransactionalIsBannedEvenInServicePackages() {
        assertThatThrownBy(() -> ArchitectureRules.NO_JAKARTA_TRANSACTIONAL.check(fixtures("jtx")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JakartaTxClassLevel")
                .hasMessageContaining("JakartaTxMethodLevel");
    }

    @Test
    void repositoryRuleFailsWhenAnotherFeatureServiceReachesIt() {
        assertThatThrownBy(() -> ArchitectureRules.REPOSITORY_ONLY_FROM_OWN_FEATURE_SERVICE
                .check(fixtures("crossfeature")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("OrdersService")
                .hasMessageContaining("CatalogRepository")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("CatalogOwnService"));
    }

    @Test
    void entityRuleFailsOnAPublicSetterAndIgnoresNonPublicOnes() {
        assertThatThrownBy(() -> ArchitectureRules.ENTITIES_HAVE_NO_PUBLIC_SETTERS.check(fixtures("entitysetter")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SettableOrder.setStatus(")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("setPackagePrivateIsFine"));
    }

    @Test
    void webTypesRuleFailsOnControllersAndAdviceOutsideControllerPackages() {
        assertThatThrownBy(() -> ArchitectureRules.WEB_TYPES_ONLY_IN_CONTROLLER_PACKAGES.check(fixtures("webtypes")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MisplacedRestControllerType")
                .hasMessageContaining("MisplacedAdviceType");
    }

    @Test
    void webFreeRuleFailsWhenAServiceTouchesTheServletApi() {
        assertThatThrownBy(() -> ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE.check(fixtures("webfree")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ServletAwareService")
                .hasMessageContaining("jakarta.servlet.http.HttpServletRequest");
    }

    @Test
    void webFreeRuleFailsWhenDomainKnowsHttpStatusesAndSpringWebExceptions() {
        assertThatThrownBy(() -> ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE.check(fixtures("webfree")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("HttpAwareDomain")
                .hasMessageContaining("org.springframework.http.HttpStatus")
                .hasMessageContaining("org.springframework.web.server.ResponseStatusException");
    }

    @Test
    void crossOriginRuleFailsOnTheAnnotationAtClassAndMethodLevel() {
        assertThatThrownBy(() -> ArchitectureRules.NO_CROSS_ORIGIN.check(fixtures("crossorigin")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CrossOriginController")
                .hasMessageContaining("CrossOriginController.open()");
    }

    @Test
    void crossOriginRuleFailsOnGlobalCorsConfiguration() {
        assertThatThrownBy(() -> ArchitectureRules.NO_CROSS_ORIGIN.check(fixtures("crossorigin")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CorsConfigurer")
                .hasMessageContaining("addCorsMappings")
                .hasMessageContaining("CorsRegistry");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "noBounds(", "onlyMin(", "onlyMax(", "query(java.lang.String, java.math.BigDecimal) parameter 0",
            "query(java.lang.String, java.math.BigDecimal) parameter 1", "header(", "headerSizeWithoutMax(",
            "UnboundedBody.unboundedQuantity", "UnboundedBody.unsizedText", "UnboundedBody.unsizedList",
            "Nested.unboundedPrice"})
    void inputsRuleFailsOnEachUnboundedInput(String expected) {
        assertThatThrownBy(() -> ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED.check(fixtures("unbounded")))
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> assertThat(error.getMessage()).contains(expected));
    }

    @Test
    void inputsRuleExplainsWhichBoundIsMissingAndIgnoresBoundedMembers() {
        assertThatThrownBy(() -> ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED.check(fixtures("unbounded")))
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .contains("lacks a maximum", "lacks a minimum", "lacks a minimum and a maximum",
                                "lacks @Size(max = ...)")
                        .doesNotContain("UnboundedBody.sizedList is", "Nested.boundedInNested is"));
    }

    @Test
    void functionalEndpointsRuleFailsOnRouterFunctionsAndServerResponses() {
        assertThatThrownBy(() -> ArchitectureRules.NO_FUNCTIONAL_ENDPOINTS.check(fixtures("functional")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FunctionalRoutes")
                .hasMessageContaining("org.springframework.web.servlet.function.RouterFunction")
                .hasMessageContaining("org.springframework.web.servlet.function.ServerResponse");
    }

    @Test
    void compliantCodeSatisfiesEveryRule() {
        JavaClasses clean = fixtures("clean");
        List<ArchRule> rules = List.of(
                ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE,
                ArchitectureRules.LAYERS,
                ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES,
                ArchitectureRules.NO_SYSTEM_CLOCK,
                ArchitectureRules.NO_JAKARTA_TRANSACTIONAL,
                ArchitectureRules.REPOSITORY_ONLY_FROM_OWN_FEATURE_SERVICE,
                ArchitectureRules.ENTITIES_HAVE_NO_PUBLIC_SETTERS,
                ArchitectureRules.WEB_TYPES_ONLY_IN_CONTROLLER_PACKAGES,
                ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE,
                ArchitectureRules.NO_CROSS_ORIGIN,
                ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED,
                ArchitectureRules.NO_FUNCTIONAL_ENDPOINTS);
        for (ArchRule rule : rules) {
            assertThatCode(() -> rule.check(clean)).doesNotThrowAnyException();
        }
    }
}
