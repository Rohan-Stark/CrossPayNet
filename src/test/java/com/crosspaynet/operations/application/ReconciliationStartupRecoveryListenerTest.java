package com.crosspaynet.operations.application;

import com.crosspaynet.operations.contracts.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationStartupRecoveryListenerTest {

    @Mock
    private ReconciliationService reconciliationService;

    @Test
    void shouldRecoverAbandonedRunsOnApplicationReady() {
        when(reconciliationService.recoverAbandonedRuns()).thenReturn(2);

        ReconciliationStartupRecoveryListener listener = new ReconciliationStartupRecoveryListener(reconciliationService);
        listener.onApplicationReady();

        verify(reconciliationService).recoverAbandonedRuns();
    }
}
