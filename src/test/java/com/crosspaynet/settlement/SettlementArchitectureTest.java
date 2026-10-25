package com.crosspaynet.settlement;

import com.crosspaynet.CrossPayNetApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SettlementArchitectureTest {

    private static final List<String> FORBIDDEN_SETTLEMENT_IMPORT_PREFIXES = List.of(
            "com.crosspaynet.banking.domain",
            "com.crosspaynet.banking.infrastructure",
            "com.crosspaynet.ledger.domain",
            "com.crosspaynet.ledger.infrastructure",
            "com.crosspaynet.payment.domain",
            "com.crosspaynet.payment.infrastructure"
    );

    @Test
    void testSpringModulithVerificationAndSettlementModuleNamedInterface() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        ApplicationModule settlementModule = modules.getModuleByName("settlement")
                .orElseThrow(() -> new AssertionError("Spring Modulith must detect 'settlement' module"));

        assertTrue(
                settlementModule.getNamedInterfaces().getByName("contracts").isPresent(),
                "settlement module must expose 'contracts' named interface"
        );
        modules.verify();
    }

    @Test
    void testSettlementModuleDoesNotImportInternalDomainOrPersistencePackagesOfOtherModules() throws IOException {
        Path settlementRootDir = Path.of("src", "main", "java", "com", "crosspaynet", "settlement");
        assertTrue(Files.isDirectory(settlementRootDir), "settlement module directory must exist");

        try (Stream<Path> paths = Files.walk(settlementRootDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty(), "settlement module must contain Java source files");

            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenPrefix : FORBIDDEN_SETTLEMENT_IMPORT_PREFIXES) {
                    assertFalse(
                            source.contains(forbiddenPrefix),
                            () -> javaFile + " must not reference internal package " + forbiddenPrefix
                    );
                }
            }
        }
    }

    @Test
    void testSettlementServicePlacementAndPublicContractSurface() {
        assertEquals("com.crosspaynet.settlement", SettlementService.class.getPackageName());
        assertTrue(Modifier.isPublic(SettlementService.class.getModifiers()));
        assertEquals("PAYMENT_SETTLEMENT:", SettlementService.SETTLEMENT_POSTING_KEY_PREFIX);

        for (Method method : SettlementService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                for (String forbiddenPrefix : FORBIDDEN_SETTLEMENT_IMPORT_PREFIXES) {
                    assertFalse(
                            method.getReturnType().getName().startsWith(forbiddenPrefix),
                            () -> "Public method " + method.getName() + " must not return internal type"
                    );
                    for (Class<?> paramType : method.getParameterTypes()) {
                        assertFalse(
                                paramType.getName().startsWith(forbiddenPrefix),
                                () -> "Public method " + method.getName() + " must not accept internal type"
                        );
                    }
                }
            }
        }
    }

    @Test
    void testPaymentModuleRemainsDecoupledFromLedgerAndSettlement() throws IOException {
        Path paymentRootDir = Path.of("src", "main", "java", "com", "crosspaynet", "payment");
        assertTrue(Files.isDirectory(paymentRootDir), "payment module directory must exist");

        try (Stream<Path> paths = Files.walk(paymentRootDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                assertFalse(
                        source.contains("com.crosspaynet.ledger"),
                        () -> javaFile + " must not depend on com.crosspaynet.ledger"
                );
                assertFalse(
                        source.contains("com.crosspaynet.settlement"),
                        () -> javaFile + " must not depend on com.crosspaynet.settlement"
                );
            }
        }
    }
}
