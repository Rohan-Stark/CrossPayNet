package com.crosspaynet.messaging;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.messaging.domain.MessagingLimits;
import com.crosspaynet.messaging.domain.Pacs008PayloadBuilder;
import com.crosspaynet.messaging.domain.PayloadDigest;
import com.crosspaynet.messaging.domain.PaymentMessage;
import com.crosspaynet.messaging.domain.PersistedMessageStateException;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MessagingArchitectureTest {

    private static final List<Class<?>> DOMAIN_CLASSES = List.of(
            MessagingLimits.class,
            PayloadDigest.class,
            Pacs008PayloadBuilder.class,
            PaymentMessage.class,
            PersistedMessageStateException.class
    );

    private static final List<String> FORBIDDEN_DOMAIN_PACKAGE_PREFIXES = List.of(
            "org.springframework.",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.hibernate.",
            "java.sql.",
            "javax.sql.",
            "com.crosspaynet.banking.",
            "com.crosspaynet.ledger.",
            "com.crosspaynet.settlement."
    );

    private static final List<String> FORBIDDEN_MODULE_IMPORT_PREFIXES = List.of(
            "com.crosspaynet.banking",
            "com.crosspaynet.ledger",
            "com.crosspaynet.settlement",
            "com.crosspaynet.payment.domain",
            "com.crosspaynet.payment.infrastructure",
            "com.crosspaynet.payment.settlement"
    );

    @Test
    void testMessagingDomainHasZeroFrameworkPersistenceBankingLedgerOrSettlementDependencies() throws IOException {
        for (Class<?> domainClass : DOMAIN_CLASSES) {
            assertEquals("com.crosspaynet.messaging.domain", domainClass.getPackageName());
            for (Annotation annotation : domainClass.getDeclaredAnnotations()) {
                assertNotForbiddenDomainType(domainClass, annotation.annotationType().getName());
            }
            for (Field field : domainClass.getDeclaredFields()) {
                assertNotForbiddenDomainType(domainClass, field.getType().getName());
                for (Annotation annotation : field.getDeclaredAnnotations()) {
                    assertNotForbiddenDomainType(domainClass, annotation.annotationType().getName());
                }
            }
            for (Constructor<?> constructor : domainClass.getDeclaredConstructors()) {
                for (Class<?> paramType : constructor.getParameterTypes()) {
                    assertNotForbiddenDomainType(domainClass, paramType.getName());
                }
            }
            for (Method method : domainClass.getDeclaredMethods()) {
                assertNotForbiddenDomainType(domainClass, method.getReturnType().getName());
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertNotForbiddenDomainType(domainClass, paramType.getName());
                }
            }
        }

        Path domainSourceDir = Path.of("src", "main", "java", "com", "crosspaynet", "messaging", "domain");
        assertTrue(Files.isDirectory(domainSourceDir), "messaging.domain source directory must exist");
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
    void testEntireMessagingModuleRespectsCrossModuleBoundaries() throws IOException {
        Path messagingRootDir = Path.of("src", "main", "java", "com", "crosspaynet", "messaging");
        assertTrue(Files.isDirectory(messagingRootDir), "messaging module directory must exist");

        try (Stream<Path> paths = Files.walk(messagingRootDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty());

            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                for (String forbiddenPrefix : FORBIDDEN_MODULE_IMPORT_PREFIXES) {
                    assertFalse(
                            source.contains("import " + forbiddenPrefix),
                            () -> javaFile + " must not import " + forbiddenPrefix
                    );
                }
            }
        }
    }

    @Test
    void testMessagingServiceAndPublicContractsNeverLeakInternalDomainOrPersistenceTypes() throws IOException {
        assertEquals("com.crosspaynet.messaging", MessagingService.class.getPackageName());
        assertTrue(Modifier.isPublic(MessagingService.class.getModifiers()));

        for (Method method : MessagingService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertFalse(
                        method.getReturnType().getName().startsWith("com.crosspaynet.messaging.domain."),
                        () -> "Public method " + method.getName() + " must not return internal domain type"
                );
                assertFalse(
                        method.getReturnType().getName().startsWith("com.crosspaynet.messaging.infrastructure."),
                        () -> "Public method " + method.getName() + " must not return internal persistence type"
                );
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertFalse(
                            paramType.getName().startsWith("com.crosspaynet.messaging.domain."),
                            () -> "Public method " + method.getName() + " must not accept internal domain type"
                    );
                    assertFalse(
                            paramType.getName().startsWith("com.crosspaynet.messaging.infrastructure."),
                            () -> "Public method " + method.getName() + " must not accept internal persistence type"
                    );
                }
            }
        }

        Path contractsDir = Path.of("src", "main", "java", "com", "crosspaynet", "messaging", "contracts");
        try (Stream<Path> paths = Files.walk(contractsDir)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                assertFalse(
                        source.contains("com.crosspaynet.messaging.domain"),
                        () -> javaFile + " must not import internal com.crosspaynet.messaging.domain"
                );
            }
        }
    }

    @Test
    void testSpringModulithVerificationAndMessagingNamedInterface() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        ApplicationModule messagingModule = modules.getModuleByName("messaging")
                .orElseThrow(() -> new AssertionError("Spring Modulith must detect 'messaging' module"));

        assertTrue(
                messagingModule.getNamedInterfaces().getByName("contracts").isPresent(),
                "messaging module must expose 'contracts' named interface"
        );
        modules.verify();
    }

    private static void assertNotForbiddenDomainType(Class<?> owner, String referencedTypeName) {
        for (String forbiddenPrefix : FORBIDDEN_DOMAIN_PACKAGE_PREFIXES) {
            assertFalse(
                    referencedTypeName.startsWith(forbiddenPrefix),
                    () -> owner.getName() + " references forbidden type " + referencedTypeName
            );
        }
    }
}
