package com.crosspaynet.compliance;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceReviewId;
import com.crosspaynet.compliance.contracts.ComplianceReviewView;
import com.crosspaynet.compliance.contracts.ComplianceService;
import com.crosspaynet.compliance.contracts.ComplianceStatus;
import com.crosspaynet.compliance.contracts.ScreenPaymentCommand;
import com.crosspaynet.compliance.contracts.SubmitComplianceDecisionCommand;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplianceModulithArchitectureTest {

    private static final JavaClasses COMPLIANCE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.crosspaynet.compliance");

    @Test
    void shouldVerifySpringModulithBoundariesWithComplianceModule() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        modules.verify();

        ApplicationModule complianceModule = modules.getModuleByName("compliance").orElseThrow();
        assertNotNull(complianceModule);

        NamedInterface contracts = complianceModule.getNamedInterfaces().getByName("contracts").orElseThrow();
        assertTrue(contracts.contains(ComplianceService.class));
        assertTrue(contracts.contains(ComplianceReviewId.class));
        assertTrue(contracts.contains(ComplianceStatus.class));
        assertTrue(contracts.contains(ComplianceDecision.class));
        assertTrue(contracts.contains(ScreenPaymentCommand.class));
        assertTrue(contracts.contains(SubmitComplianceDecisionCommand.class));
        assertTrue(contracts.contains(ComplianceReviewView.class));
    }

    @Test
    void complianceDomainMustBePureJavaAndFreeOfFrameworkAndDatabaseDependencies() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.compliance.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "java.sql..",
                        "javax.sql..",
                        "com.crosspaynet.compliance.infrastructure..",
                        "com.crosspaynet.banking..",
                        "com.crosspaynet.ledger..",
                        "com.crosspaynet.messaging..",
                        "com.crosspaynet.routing..",
                        "com.crosspaynet.settlement.."
                )
                .check(COMPLIANCE_CLASSES);
    }
}
