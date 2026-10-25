package com.crosspaynet.routing;

import com.crosspaynet.CrossPayNetApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RoutingArchitectureTest {

    private static final JavaClasses ROUTING_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.crosspaynet.routing");

    @Test
    void shouldVerifySpringModulithBoundariesWithRoutingModule() {
        ApplicationModules.of(CrossPayNetApplication.class).verify();
    }

    @Test
    void routingDomainMustBePureJavaAndFreeOfFrameworkAndDatabaseDependencies() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.routing.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "java.sql..",
                        "javax.sql..",
                        "com.crosspaynet.routing.infrastructure..",
                        "com.crosspaynet.banking..",
                        "com.crosspaynet.ledger..",
                        "com.crosspaynet.messaging..",
                        "com.crosspaynet.settlement.."
                )
                .check(ROUTING_CLASSES);
    }

    @Test
    void routingContractsMustRemainFrameworkFreeAndDecoupledFromDomainAndInfrastructure() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.routing.contracts..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.crosspaynet.routing.domain..",
                        "com.crosspaynet.routing.infrastructure..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "java.sql..",
                        "javax.sql..",
                        "com.crosspaynet.banking..",
                        "com.crosspaynet.ledger..",
                        "com.crosspaynet.messaging..",
                        "com.crosspaynet.settlement.."
                )
                .check(ROUTING_CLASSES);
    }

    @Test
    void routingModuleMustNotAccessBankingLedgerMessagingSettlementOrPaymentInternals() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.routing..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.crosspaynet.banking..",
                        "com.crosspaynet.ledger..",
                        "com.crosspaynet.messaging..",
                        "com.crosspaynet.settlement..",
                        "com.crosspaynet.payment.domain..",
                        "com.crosspaynet.payment.infrastructure..",
                        "com.crosspaynet.payment.settlement.."
                )
                .check(ROUTING_CLASSES);
    }
}
