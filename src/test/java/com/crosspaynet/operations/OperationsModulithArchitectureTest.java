package com.crosspaynet.operations;

import com.crosspaynet.CrossPayNetApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OperationsModulithArchitectureTest {

    private static final JavaClasses OPERATIONS_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.crosspaynet.operations");

    @Test
    void shouldVerifySpringModulithBoundariesWithOperationsModule() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        modules.verify();

        ApplicationModule operationsModule = modules.getModuleByName("operations").orElseThrow();
        assertNotNull(operationsModule);
    }

    @Test
    void operationsDomainMustBePureJavaAndFreeOfFrameworkAndDatabaseDependencies() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.operations.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate.."
                )
                .because("Operations domain layer must be pure Java and agnostic of persistence/framework details")
                .check(OPERATIONS_CLASSES);
    }

    @Test
    void operationsMustNotDependOnBankingRootPackage() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.operations..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.crosspaynet.banking")
                .because("Operations must depend only on named interface 'banking :: contracts' (e.g. BankingReservationQueryPort) and never on BankingService or internal banking root package")
                .check(OPERATIONS_CLASSES);
    }

    @Test
    void operationsMustNotDependOnLedgerRootPackage() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.operations..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.crosspaynet.ledger")
                .because("Operations must depend only on named interface 'ledger :: contracts' (e.g. LedgerPostingQueryPort) and never on LedgerService or root ledger package")
                .check(OPERATIONS_CLASSES);
    }

    @Test
    void operationsMustNotDependOnEventsRootPackage() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.operations..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.crosspaynet.events")
                .because("Operations must depend only on named interface 'events :: contracts' (e.g. EventPublicationQueryPort) and never on root events package")
                .check(OPERATIONS_CLASSES);
    }

    @Test
    void operationsMustNotDirectlyAccessOtherModulesInternalPersistence() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.operations..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.crosspaynet.banking.infrastructure..",
                        "com.crosspaynet.payment.infrastructure..",
                        "com.crosspaynet.ledger.infrastructure..",
                        "com.crosspaynet.compliance.infrastructure..",
                        "com.crosspaynet.messaging.infrastructure..",
                        "com.crosspaynet.routing.infrastructure..",
                        "com.crosspaynet.settlement.infrastructure.."
                )
                .because("Operations must not access internal persistence layer of other modules")
                .check(OPERATIONS_CLASSES);
    }
}
