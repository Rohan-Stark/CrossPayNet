package com.crosspaynet.security;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.security.contracts.SecuredPaymentPortalService;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityModulithArchitectureTest {

    @Test
    void shouldVerifySpringModulithBoundariesWithSecurityModule() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        modules.verify();

        ApplicationModule securityModule = modules.getModuleByName("security").orElseThrow();
        assertNotNull(securityModule);

        NamedInterface contracts = securityModule.getNamedInterfaces().getByName("contracts").orElseThrow();
        assertTrue(contracts.contains(SecuredPaymentPortalService.class));
    }
}
