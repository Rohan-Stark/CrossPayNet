package com.crosspaynet.payment;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.payment.domain.InstructionFingerprint;
import com.crosspaynet.payment.domain.Payment;
import com.crosspaynet.payment.domain.PaymentLimits;
import com.crosspaynet.payment.domain.PaymentSupportedCurrencyPolicy;
import com.crosspaynet.payment.domain.PersistedPaymentStateException;
import com.crosspaynet.payment.settlement.PaymentSettlementPort;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PaymentArchitectureTest {

    private static final List<Class<?>> DOMAIN_CLASSES = List.of(
            Payment.class,
            PaymentLimits.class,
            PaymentSupportedCurrencyPolicy.class,
            InstructionFingerprint.class,
            PersistedPaymentStateException.class
    );

    private static final List<String> FORBIDDEN_DOMAIN_PACKAGE_PREFIXES = List.of(
            "org.springframework.",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.hibernate.",
            "java.sql.",
            "javax.sql.",
            "com.crosspaynet.ledger."
    );

    @Test
    void testPaymentDomainHasZeroFrameworkPersistenceOrLedgerDependencies() throws IOException {
        for (Class<?> domainClass : DOMAIN_CLASSES) {
            assertEquals("com.crosspaynet.payment.domain", domainClass.getPackageName());
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

        Path domainSourceDir = Path.of("src", "main", "java", "com", "crosspaynet", "payment", "domain");
        assertTrue(Files.isDirectory(domainSourceDir), "payment.domain source directory must exist");
        try (Stream<Path> paths = Files.walk(domainSourceDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            assertEquals(5, javaFiles.size());
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenPrefix : FORBIDDEN_DOMAIN_PACKAGE_PREFIXES) {
                    assertFalse(
                            source.contains("import " + forbiddenPrefix),
                            () -> javaFile + " must not import " + forbiddenPrefix
                    );
                }
            }
        }
    }

    @Test
    void testEntirePaymentModuleHasZeroLedgerImports() throws IOException {
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
                        source.contains("com.crosspaynet.banking.domain"),
                        () -> javaFile + " must not depend on internal com.crosspaynet.banking.domain"
                );
            }
        }
    }

    @Test
    void testPublicContractsAndSettlementPortDoNotImportInternalPaymentDomain() throws IOException {
        for (String subpackage : List.of("contracts", "settlement")) {
            Path dir = Path.of("src", "main", "java", "com", "crosspaynet", "payment", subpackage);
            try (Stream<Path> paths = Files.walk(dir)) {
                List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
                for (Path javaFile : javaFiles) {
                    String source = Files.readString(javaFile);
                    assertFalse(
                            source.contains("com.crosspaynet.payment.domain"),
                            () -> javaFile + " must not import internal com.crosspaynet.payment.domain"
                    );
                }
            }
        }
    }

    @Test
    void testPaymentLimitsAndSupportedCurrencyPolicy() {
        assertEquals(4, PaymentLimits.MAX_SCALE);
        assertEquals(19, PaymentLimits.MAX_NUMERIC_PRECISION);
        assertEquals(new BigDecimal("999999999999999.9999"), PaymentLimits.MAX_AMOUNT);
        assertEquals(255, PaymentLimits.MAX_PAYMENT_REFERENCE_LENGTH);
        assertEquals(140, PaymentLimits.MAX_BENEFICIARY_NAME_LENGTH);
        assertEquals(34, PaymentLimits.MAX_BENEFICIARY_ACCOUNT_LENGTH);
        assertEquals(8, PaymentLimits.MIN_BIC_LENGTH);
        assertEquals(11, PaymentLimits.MAX_BIC_LENGTH);
        assertEquals(100, PaymentLimits.MAX_ROUTE_REFERENCE_LENGTH);
        assertEquals(100, PaymentLimits.MAX_EXTERNAL_DISPATCH_REFERENCE_LENGTH);
        assertEquals(500, PaymentLimits.MAX_REASON_DETAIL_LENGTH);

        assertEquals(
                Set.of("USD", "EUR", "GBP", "INR", "JPY", "CHF", "SGD", "AED"),
                PaymentSupportedCurrencyPolicy.supportedCodes()
        );
    }

    @Test
    void testPaymentServicePlacementAndSettlementPortContract() throws IOException {
        assertEquals("com.crosspaynet.payment", PaymentService.class.getPackageName());
        assertTrue(Modifier.isPublic(PaymentService.class.getModifiers()));
        assertTrue(PaymentSettlementPort.class.isAssignableFrom(PaymentService.class));

        // Verify public methods of PaymentService never leak internal domain or persistence types
        for (Method method : PaymentService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertFalse(
                        method.getReturnType().getName().startsWith("com.crosspaynet.payment.domain."),
                        () -> "Public method " + method.getName() + " must not return internal domain type"
                );
                assertFalse(
                        method.getReturnType().getName().startsWith("com.crosspaynet.payment.infrastructure."),
                        () -> "Public method " + method.getName() + " must not return internal persistence type"
                );
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertFalse(
                            paramType.getName().startsWith("com.crosspaynet.payment.domain."),
                            () -> "Public method " + method.getName() + " must not accept internal domain type"
                    );
                    assertFalse(
                            paramType.getName().startsWith("com.crosspaynet.payment.infrastructure."),
                            () -> "Public method " + method.getName() + " must not accept internal persistence type"
                    );
                }
            }
        }

        // Verify PaymentService source does not invoke BankingService.consumeReservation or LedgerService
        Path serviceSourcePath = Path.of("src", "main", "java", "com", "crosspaynet", "payment", "PaymentService.java");
        assertTrue(Files.isRegularFile(serviceSourcePath), "PaymentService.java must exist");
        String serviceSource = Files.readString(serviceSourcePath);
        assertFalse(
                serviceSource.contains("consumeReservation"),
                "PaymentService must not invoke BankingService.consumeReservation"
        );
        assertFalse(
                serviceSource.contains("LedgerService") || serviceSource.contains("postTransaction"),
                "PaymentService must not invoke LedgerService or postTransaction"
        );
    }

    @Test
    void testSpringModulithVerificationAndNamedInterfaces() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        ApplicationModule paymentModule = modules.getModuleByName("payment")
                .orElseThrow(() -> new AssertionError("Spring Modulith must detect 'payment' module"));

        assertTrue(
                paymentModule.getNamedInterfaces().getByName("contracts").isPresent(),
                "payment module must expose 'contracts' named interface"
        );
        assertTrue(
                paymentModule.getNamedInterfaces().getByName("settlement").isPresent(),
                "payment module must expose 'settlement' named interface"
        );
        modules.verify();
    }

    private static void assertNotForbidden(Class<?> owner, String referencedTypeName) {
        for (String forbiddenPrefix : FORBIDDEN_DOMAIN_PACKAGE_PREFIXES) {
            assertFalse(
                    referencedTypeName.startsWith(forbiddenPrefix),
                    () -> owner.getName() + " references forbidden type " + referencedTypeName
            );
        }
    }
}
