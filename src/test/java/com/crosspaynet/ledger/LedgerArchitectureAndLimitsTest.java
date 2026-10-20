package com.crosspaynet.ledger;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.ledger.domain.LedgerEntry;
import com.crosspaynet.ledger.domain.LedgerLimits;
import com.crosspaynet.ledger.domain.LedgerTransaction;
import com.crosspaynet.ledger.domain.PostingFingerprint;
import com.crosspaynet.ledger.domain.SupportedCurrencyPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LedgerArchitectureAndLimitsTest {

    private static final List<Class<?>> DOMAIN_CLASSES = List.of(
            LedgerTransaction.class,
            LedgerEntry.class,
            PostingFingerprint.class,
            SupportedCurrencyPolicy.class,
            LedgerLimits.class
    );

    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "org.springframework.",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.hibernate.",
            "java.sql.",
            "javax.sql.",
            "com.crosspaynet.banking."
    );

    @Test
    void testLedgerDomainHasZeroFrameworkPersistenceOrBankingDependencies() throws IOException {
        // 1. Inspect compiled class signatures, fields, constructors, methods, and annotations
        for (Class<?> domainClass : DOMAIN_CLASSES) {
            assertEquals("com.crosspaynet.ledger.domain", domainClass.getPackageName());
            for (Annotation annotation : domainClass.getDeclaredAnnotations()) {
                assertNotForbidden(domainClass, annotation.annotationType().getName());
            }
            for (Field field : domainClass.getDeclaredFields()) {
                assertNotForbidden(domainClass, field.getType().getName());
                for (Annotation annotation : field.getDeclaredAnnotations()) {
                    assertNotForbidden(domainClass, annotation.annotationType().getName());
                }
            }
            for (Constructor<?> constructor : domainClass.getDeclaredConstructors()) {
                for (Class<?> paramType : constructor.getParameterTypes()) {
                    assertNotForbidden(domainClass, paramType.getName());
                }
            }
            for (Method method : domainClass.getDeclaredMethods()) {
                assertNotForbidden(domainClass, method.getReturnType().getName());
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertNotForbidden(domainClass, paramType.getName());
                }
            }
        }

        // 2. Inspect all source files under src/main/java/com/crosspaynet/ledger/domain
        Path domainSourceDir = Path.of("src", "main", "java", "com", "crosspaynet", "ledger", "domain");
        assertTrue(Files.isDirectory(domainSourceDir), "ledger.domain source directory must exist");
        try (Stream<Path> paths = Files.walk(domainSourceDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            assertEquals(5, javaFiles.size());
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenPrefix : FORBIDDEN_PACKAGE_PREFIXES) {
                    assertFalse(
                            source.contains("import " + forbiddenPrefix),
                            () -> javaFile + " must not import " + forbiddenPrefix
                    );
                }
            }
        }
    }

    @Test
    void testLedgerLimitsAndSupportedCurrencyPolicyMatchAdr006() {
        assertEquals(2, LedgerLimits.MIN_ENTRIES);
        assertEquals(100, LedgerLimits.MAX_ENTRIES);
        assertEquals(4, LedgerLimits.MAX_SCALE);
        assertEquals(19, LedgerLimits.MAX_NUMERIC_PRECISION);
        assertEquals(new BigDecimal("999999999999999.9999"), LedgerLimits.MAX_AMOUNT);
        assertEquals(200, LedgerLimits.MAX_POSTING_KEY_LENGTH);
        assertEquals("REVERSAL:", LedgerLimits.REVERSAL_POSTING_KEY_PREFIX);
        assertEquals(255, LedgerLimits.MAX_EXTERNAL_REFERENCE_LENGTH);
        assertEquals(500, LedgerLimits.MAX_DESCRIPTION_LENGTH);
        assertEquals(100, LedgerLimits.MAX_ACCOUNT_REFERENCE_LENGTH);

        assertEquals(
                Set.of("USD", "EUR", "GBP", "INR", "JPY", "CHF", "SGD", "AED"),
                SupportedCurrencyPolicy.supportedCodes()
        );
    }

    @Test
    void testSpringModulithVerificationWithLedgerModule() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        assertTrue(modules.getModuleByName("ledger").isPresent(), "Spring Modulith must detect 'ledger' module");
        modules.verify();
    }

    private static void assertNotForbidden(Class<?> owner, String referencedTypeName) {
        for (String forbiddenPrefix : FORBIDDEN_PACKAGE_PREFIXES) {
            assertFalse(
                    referencedTypeName.startsWith(forbiddenPrefix),
                    () -> owner.getName() + " references forbidden type " + referencedTypeName
            );
        }
    }
}
