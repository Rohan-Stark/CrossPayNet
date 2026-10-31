package com.crosspaynet.security;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
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

class SecurityModulithArchitectureTest {

    private static final JavaClasses SECURITY_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.crosspaynet.security");

    @Test
    void shouldVerifySpringModulithBoundariesWithSecurityModule() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        modules.verify();

        ApplicationModule securityModule = modules.getModuleByName("security").orElseThrow();
        assertNotNull(securityModule);

        NamedInterface contracts = securityModule.getNamedInterfaces().getByName("contracts").orElseThrow();
        assertTrue(contracts.contains(SecuredPaymentPortalService.class));
    }

    @Test
    void portalWebControllersMustNotDependOnPersistenceDirectly() {
        noClasses()
                .that().resideInAPackage("com.crosspaynet.security.portal.web..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.crosspaynet..infrastructure.persistence..",
                        "org.springframework.data..",
                        "jakarta.persistence.."
                )
                .because("Web controllers must strictly delegate to SecuredPaymentPortalService and never access persistence repositories or entities directly")
                .check(SECURITY_CLASSES);
    }
}

