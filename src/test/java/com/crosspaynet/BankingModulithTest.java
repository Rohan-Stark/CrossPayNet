package com.crosspaynet;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class BankingModulithTest {

    @Test
    void verifyModularity() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        
        // Verify module boundaries are respected (e.g., no cyclic dependencies, infrastructure not exposed)
        modules.verify();
    }
}
