package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.CetoBackendApplication;
import ar.cetobhb.common.domain.DomainException;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
