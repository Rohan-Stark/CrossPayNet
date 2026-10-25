package com.crosspaynet;

import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.FundsReserved;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.banking.contracts.AccountNotFoundException;
import com.crosspaynet.banking.contracts.BankingException;
import com.crosspaynet.banking.contracts.CurrencyMismatchException;
import com.crosspaynet.banking.contracts.InsufficientFundsException;
import com.crosspaynet.banking.contracts.InvalidAccountStateException;
import com.crosspaynet.banking.contracts.InvalidMoneyException;
import com.crosspaynet.banking.contracts.InvalidReservationStateException;
import com.crosspaynet.banking.contracts.PaymentReference;
import com.crosspaynet.banking.contracts.ReservationStatus;
import com.crosspaynet.banking.contracts.ReservationToken;
import com.crosspaynet.banking.domain.Account;
import com.crosspaynet.banking.domain.FundReservation;
import com.crosspaynet.banking.domain.PersistedBankingStateException;
import com.crosspaynet.banking.infrastructure.persistence.AccountEntity;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class BankingModulithTest {

    @Test
    void verifyModularity() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);

        // Verify module boundaries are respected (no cyclic dependencies, internal packages not exposed)
        modules.verify();

        ApplicationModule banking = modules.getModuleByName("banking").orElseThrow();

        NamedInterface unnamedRoot = banking.getNamedInterfaces().getUnnamedInterface();
        assertTrue(unnamedRoot.contains(BankingService.class));
        assertTrue(unnamedRoot.contains(FundsReserved.class));

        NamedInterface contracts = banking.getNamedInterfaces().getByName("contracts").orElseThrow();
        assertTrue(contracts.contains(AccountId.class));
        assertTrue(contracts.contains(PaymentReference.class));
        assertTrue(contracts.contains(ReservationToken.class));
        assertTrue(contracts.contains(ReservationStatus.class));
        assertTrue(contracts.contains(BankingException.class));
        assertTrue(contracts.contains(AccountNotFoundException.class));
        assertTrue(contracts.contains(InsufficientFundsException.class));
        assertTrue(contracts.contains(InvalidAccountStateException.class));
        assertTrue(contracts.contains(CurrencyMismatchException.class));
        assertTrue(contracts.contains(InvalidReservationStateException.class));
        assertTrue(contracts.contains(InvalidMoneyException.class));

        // Internal domain and persistence types must not be exposed in any named interface
        for (NamedInterface ni : banking.getNamedInterfaces()) {
            assertFalse(ni.contains(Account.class));
            assertFalse(ni.contains(FundReservation.class));
            assertFalse(ni.contains(PersistedBankingStateException.class));
            assertFalse(ni.contains(AccountEntity.class));
        }

        // Public BankingService methods must never expose internal domain or persistence types
        for (Method method : BankingService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertFalse(method.getReturnType().getName().startsWith("com.crosspaynet.banking.domain."));
                assertFalse(method.getReturnType().getName().startsWith("com.crosspaynet.banking.infrastructure."));
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertFalse(paramType.getName().startsWith("com.crosspaynet.banking.domain."));
                    assertFalse(paramType.getName().startsWith("com.crosspaynet.banking.infrastructure."));
                }
            }
        }
    }
}
