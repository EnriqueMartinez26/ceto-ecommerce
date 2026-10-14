package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.CetoBackendApplication;
import ar.cetobhb.common.domain.DomainException;
import ar.cetobhb.common.domain.RateLimitedException;
import ar.cetobhb.orders.domain.CheckoutInProgressException;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.TooManyPendingOrdersException;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/** REQ-ERR-03: a new DomainException subclass without a table entry must fail the build, not fall through to 500. */
class ApiErrorCatalogTest {

    /** A subclass nobody registered: proves the scan below would notice a missing entry. */
    private static final class UnregisteredException extends DomainException {
        private static final long serialVersionUID = 1L;

        UnregisteredException() {
            super("UNREGISTERED");
        }
    }

    @Test
    void everyConcreteDomainExceptionHasAnExplicitEntry() {
        List<String> missing = missingEntries(concreteDomainExceptions(false), ApiErrorCatalog.domainErrors());

        assertThat(missing).isEmpty();
    }

    @Test
    void theScanSeesTheRealSubclasses() {
        assertThat(concreteDomainExceptions(false)).hasSizeGreaterThanOrEqualTo(9);
    }

    @Test
    void anUnregisteredSubclassIsReported() {
        List<String> missing = missingEntries(concreteDomainExceptions(true), ApiErrorCatalog.domainErrors());

        assertThat(missing).containsExactly(UnregisteredException.class.getName());
    }

    @Test
    void everyEntryHasAFixedNeutralDetailAndAStableCode() {
        ApiErrorCatalog.domainErrors().values().forEach(error -> {
            assertThat(error.code()).matches("[A-Z][A-Z_]+");
            assertThat(error.detail()).isNotBlank().doesNotContain("%", "{", "[");
        });
    }

    @Test
    void thePendingCapRejectionLogsAtDebugSoAbuseCannotFloodTheLog() {
        // A client can trigger this 429 at will: anything above DEBUG would let abuse flood the production log.
        assertThat(ApiErrorCatalog.domainErrors().get(TooManyPendingOrdersException.class).logLevel())
                .isEqualTo(Level.DEBUG);
    }

    @Test
    void aCheckoutInProgressIsABusyServiceLoggedAtDebugBecauseAClientCanTriggerItAtWill() {
        ApiError row = ApiErrorCatalog.domainErrors().get(CheckoutInProgressException.class);

        assertThat(row.status().value()).isEqualTo(503);
        assertThat(row.code()).isEqualTo("SERVICE_BUSY");
        assertThat(row.detail()).isEqualTo(ApiErrorCatalog.SERVICE_BUSY.detail());
        assertThat(row.logLevel()).isEqualTo(Level.DEBUG);
        assertThat(new CheckoutInProgressException()).hasMessage("CHECKOUT_IN_PROGRESS");
    }

    /** ERR-06: an illegal edge (a double tap, a stale screen) is its own 409 code, not the generic conflict. */
    @Test
    void anIllegalTransitionIsItsOwn409LoggedAtInfoWithFixedText() {
        ApiError row = ApiErrorCatalog.domainErrors().get(IllegalOrderTransitionException.class);

        assertThat(row.status().value()).isEqualTo(409);
        assertThat(row.code()).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(row.detail()).isEqualTo("The order cannot move to that state from its current one.");
        assertThat(row.logLevel()).isEqualTo(Level.INFO);
        assertThat(row).isNotEqualTo(ApiErrorCatalog.CONFLICT);
    }

    @Test
    void anUnknownOrderIsTheNeutralNotFoundWithoutALogLine() {
        assertThat(ApiErrorCatalog.domainErrors().get(OrderNotFoundException.class)).isEqualTo(ApiErrorCatalog.NOT_FOUND);
        assertThat(ApiErrorCatalog.NOT_FOUND.logLevel()).isNull();
    }

    @Test
    void theRateLimitRejectionLogsAtDebugAndNeverCarriesTheClient() {
        assertThat(ApiErrorCatalog.domainErrors().get(RateLimitedException.class).logLevel()).isEqualTo(Level.DEBUG);
        assertThat(new RateLimitedException(7)).hasMessage("RATE_LIMITED");
    }

    private static List<String> missingEntries(List<String> scanned,
            Map<Class<? extends DomainException>, ApiError> table) {
        List<String> registered = table.keySet().stream().map(Class::getName).toList();
        return scanned.stream().filter(name -> !registered.contains(name)).sorted().toList();
    }

    private static List<String> concreteDomainExceptions(boolean includeTests) {
        ImportOption option = includeTests ? new ImportOption.OnlyIncludeTests() : new ImportOption.DoNotIncludeTests();
        Stream<JavaClass> main = new ClassFileImporter().withImportOption(option)
                .importPackagesOf(CetoBackendApplication.class).stream();
        return main.filter(c -> c.isAssignableTo(DomainException.class))
                .filter(c -> !c.isInterface() && !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(c -> !c.getName().equals(DomainException.class.getName()))
                .filter(c -> includeTests == c.getName().contains("UnregisteredException"))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }
}
