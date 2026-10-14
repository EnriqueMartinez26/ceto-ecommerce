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
import org.junit.jupiter.params.provider.CsvSource;
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

    /** The violation lines of a rule on a fixture package: one entry per reported violation. */
    private static List<String> violations(ArchRule rule, String fixture) {
        try {
            rule.check(fixtures(fixture));
        } catch (AssertionError expected) {
            return expected.getMessage().lines().toList();
        }
        throw new AssertionError("expected " + rule.getDescription() + " to fail on " + fixture);
    }

    private static void assertViolation(List<String> lines, String where, String reason) {
        assertThat(lines).as("a violation about '%s' that %s", where, reason)
                .anyMatch(line -> line.contains(where) && line.contains(reason));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            ".topLevelListInput(|lacks @Size(max = ...)",
            ".topLevelMapInput(|lacks @Size(max = ...)",
            ".rawStringInput(|lacks @Size(max = ...)",
            ".rawLongInput(|lacks a minimum and a maximum",
            ".arrayInput(|lacks @Size(max = ...)",
            ".mapInput(|lacks @Size(max = ...)",
            ".optionalNumberInput(|lacks a minimum and a maximum",
            ".optionalTextInput(|lacks @Size(max = ...)",
            "ModelInput.modelName|lacks @Size(max = ...)",
            "PlainInput.plainCount|lacks a minimum and a maximum",
            ".unvalidatedBodyInput(|is a @RequestBody and lacks @Valid",
            ".unvalidatedModelInput(|is a model object and lacks @Valid",
            "Holder.unvalidatedMember |is a nested DTO or a collection of DTOs and lacks @Valid",
            "Holder.unvalidatedMembers |is a nested DTO or a collection of DTOs and lacks @Valid"})
    void inputsRuleFailsOnHiddenContainersOptionalsModelObjectsAndMissingValid(String where, String reason) {
        assertViolation(violations(ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED, "unbounded"), where, reason);
    }

    @Test
    void inputsRuleIgnoresValidatedBoundedMembersAndEnumsWithFields() {
        List<String> lines = violations(ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED, "unbounded");

        assertThat(lines).noneMatch(line -> line.contains("Kind.wireValue"))
                .noneMatch(line -> line.contains("Holder.validatedMember"))
                .noneMatch(line -> line.contains(".plainObjectInput(") && line.contains("lacks @Valid"))
                .noneMatch(line -> line.contains(".modelAttributeInput(") && line.contains("lacks @Valid"))
                .noneMatch(line -> line.contains("Item.count") || line.contains("Item.label"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "BootWebAwareService|org.springframework.boot.web.server.WebServer",
            "BootWebAwareService|org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController"})
    void webFreeRuleFailsOnBootWebTypes(String where, String type) {
        assertViolation(violations(ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE, "webfree"), where, type);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SecurityAwareService|org.springframework.security.core.context.SecurityContextHolder",
            "SecurityAwareService|org.springframework.security.core.Authentication",
            "SecurityAwareService|org.springframework.security.core.context.SecurityContext",
            "SecurityAwareDomain|org.springframework.security.core.userdetails.UserDetails"})
    void webFreeRuleFailsWhenAServiceOrDomainTypeUsesSpringSecurity(String where, String type) {
        assertViolation(violations(ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE, "webfree"), where, type);
    }

    @Test
    void webFreeRuleSeesASecurityContextReadAsAMethodReference() {
        List<String> lines = violations(ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE, "webfree");

        assertThat(lines).anyMatch(line -> line.contains("SecurityAwareService.contextSource")
                && line.contains("SecurityContextHolder"));
    }

    @Test
    void webFreeRuleOnlyAppliesToFeatureLayerPackagesNotToServicePackagesUnderWeb() {
        assertThatCode(() -> ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE.check(fixtures("webexempt")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CorsTypes|org.springframework.web.filter.CorsFilter",
            "CorsTypes|org.springframework.web.servlet.config.annotation.CorsRegistration"})
    void crossOriginRuleNamesTheCorsFilterAndTheCorsRegistration(String where, String type) {
        assertViolation(violations(ArchitectureRules.NO_CROSS_ORIGIN, "crossorigin"), where, type);
    }

    @Test
    void functionalEndpointsRuleFailsOnRouterFunctionsAndServerResponses() {
        assertThatThrownBy(() -> ArchitectureRules.NO_FUNCTIONAL_ENDPOINTS.check(fixtures("functional")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FunctionalRoutes")
                .hasMessageContaining("org.springframework.web.servlet.function.RouterFunction")
                .hasMessageContaining("org.springframework.web.servlet.function.ServerResponse");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "BadAdminPrefixController.plural(|/api/admins/x|must map under /api/admin/",
            "BadAdminPrefixController.missingApiSegment(|/admin/x|must map under /api/admin/",
            "BadAdminPrefixController.noPathAtAll(|/|must map under /api/admin/",
            "TypoAdminTypeController.typoAtTypeLevel(|/api/admn/orders|must map under /api/admin/",
            "StrayAdminPathController.adminPathInAPublicPackage(|/api/admin/leak|may map under /api/admin",
            "StrayAdminPathController$OtherCase.adminPathByCase(|/API/Admin/leak-by-case"
                    + "|may map under /api/admin"})
    void adminPrefixRuleFailsOnEveryMisplacedPath(String where, String path, String reason) {
        List<String> lines = violations(ArchitectureRules.ADMIN_HANDLER_PATHS_ARE_UNDER_THE_ADMIN_PREFIX,
                "adminprefix");

        assertViolation(lines, where, "maps '" + path + "'");
        assertViolation(lines, where, reason);
    }

    @Test
    void adminPrefixRuleIgnoresCompliantHandlers() {
        List<String> lines = violations(ArchitectureRules.ADMIN_HANDLER_PATHS_ARE_UNDER_THE_ADMIN_PREFIX,
                "adminprefix");

        assertThat(lines).noneMatch(line -> line.contains(".fine(") || line.contains(".publicPath("));
    }

    @Test
    void adminPrefixRuleAcceptsAnAdminControllerWithTypeAndMethodPathsUnderThePrefix() {
        assertThatCode(() -> ArchitectureRules.ADMIN_HANDLER_PATHS_ARE_UNDER_THE_ADMIN_PREFIX
                .check(fixtures("clean.admin"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            ".size(java.lang.Integer) parameter 0|is numeric and lacks a minimum and a maximum",
            ".text(java.lang.String) parameter 0|lacks @Size(max = ...)",
            ".list(java.util.List) parameter 0|lacks @Size(max = ...)",
            ".after(java.lang.Long) parameter 0|is numeric and lacks a maximum",
            ".body(archfixtures.ar.cetobhb.adminbounds.admin.controller.UnboundedAdminController$AdminBody) parameter 0"
                    + "|is a @RequestBody and lacks @Valid"})
    void inputsRuleBitesOnHandlersOfAnAdminControllerThatIsCorrectlyUnderTheAdminPrefix(String where, String reason) {
        List<String> lines = violations(ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED, "adminbounds");

        assertViolation(lines, where, reason);
        assertThat(lines).noneMatch(line -> line.contains("AdminBody.label"));
        assertThatCode(() -> ArchitectureRules.ADMIN_HANDLER_PATHS_ARE_UNDER_THE_ADMIN_PREFIX
                .check(fixtures("adminbounds"))).doesNotThrowAnyException();
    }

    @Test
    void inputsRuleAcceptsTheParameterShapesOfAPagedAdminList() {
        assertThatCode(() -> ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED.check(fixtures("clean.admin")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ForwardingController.forwardCall(|jakarta.servlet.RequestDispatcher.forward(",
            "ForwardingController.includeCall(|jakarta.servlet.RequestDispatcher.include(",
            "MethodReferenceForwarder.forwardReference(|jakarta.servlet.RequestDispatcher.forward(",
            "MethodReferenceForwarder.includeReference(|jakarta.servlet.RequestDispatcher.include("})
    void forwardRuleFailsOnCallsAndMethodReferencesToTheDispatcher(String where, String target) {
        assertViolation(violations(ArchitectureRules.NO_FORWARD_OR_INCLUDE, "forwarding"), where, target);
    }

    @Test
    void forwardRuleFailsOnAPlainControllerWhoseStringsAreViewNames() {
        assertViolation(violations(ArchitectureRules.NO_FORWARD_OR_INCLUDE, "forwarding"), "ForwardingController",
                "is not meta-annotated with @ResponseBody");
    }

    @Test
    void forwardRuleFailsOnModelAndViewEvenInARestController() {
        assertViolation(violations(ArchitectureRules.NO_FORWARD_OR_INCLUDE, "forwarding"), "ViewNameController",
                "org.springframework.web.servlet.ModelAndView");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "LeakyRecords$NoOverride|prints its component 'buyerName' in toString()",
            "LeakyRecords$OverrideThatLeaks|prints its component 'phone' in toString()",
            "LeakyRecords$Outer$Line|prints its component 'productName' in toString()"})
    void toStringRuleBitesOnRecordsThatPrintPersonalComponents(String where, String reason) {
        assertViolation(violations(ArchitectureRules.RECORDS_WITH_PERSONAL_COMPONENTS_HIDE_THEM_IN_TO_STRING, "pii"),
                where, reason);
    }

    @Test
    void toStringRuleDoesNotFlagTheOuterRecordThatHidesItsValues() {
        List<String> lines = violations(ArchitectureRules.RECORDS_WITH_PERSONAL_COMPONENTS_HIDE_THEM_IN_TO_STRING,
                "pii");

        assertThat(lines).noneMatch(line -> line.contains("LeakyRecords$Outer ") || line.contains("LeakyRecords$Outer'"));
    }

    @Test
    void toStringRuleAcceptsRecordsThatHideTheirPersonalComponentsNestedOnesIncluded() {
        assertThatCode(() -> ArchitectureRules.RECORDS_WITH_PERSONAL_COMPONENTS_HIDE_THEM_IN_TO_STRING
                .check(fixtures("clean.admin"))).doesNotThrowAnyException();
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
                ArchitectureRules.NO_FUNCTIONAL_ENDPOINTS,
                ArchitectureRules.ADMIN_HANDLER_PATHS_ARE_UNDER_THE_ADMIN_PREFIX,
                ArchitectureRules.NO_FORWARD_OR_INCLUDE,
                ArchitectureRules.RECORDS_WITH_PERSONAL_COMPONENTS_HIDE_THEM_IN_TO_STRING);
        for (ArchRule rule : rules) {
            assertThatCode(() -> rule.check(clean)).doesNotThrowAnyException();
        }
    }
}
