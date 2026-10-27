package com.crosspaynet.operations.application.check;

import com.crosspaynet.events.contracts.EventPublicationCursor;
import com.crosspaynet.events.contracts.EventPublicationPage;
import com.crosspaynet.events.contracts.EventPublicationQueryPort;
import com.crosspaynet.events.contracts.StalePublicationView;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationCheckStatus;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.configuration.OperationsReconciliationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncompleteEventPublicationCheckTest {

    @Mock
    private EventPublicationQueryPort eventPublicationQueryPort;

    @Mock
    private DiscrepancyRecorder discrepancyRecorder;

    private OperationsReconciliationProperties properties;
    private IncompleteEventPublicationCheck check;

    @BeforeEach
    void setUp() {
        properties = new OperationsReconciliationProperties();
        properties.setStaleEventPublicationThreshold(Duration.ofMinutes(15));
        properties.setPageSize(50);

        check = new IncompleteEventPublicationCheck(
                eventPublicationQueryPort,
                discrepancyRecorder,
                properties
        );
    }

    @Test
    void shouldDetectIncompleteEventPublication() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();
        UUID pubId = UUID.randomUUID();
        Instant pubDate = now.minus(20, ChronoUnit.MINUTES);

        StalePublicationView view = new StalePublicationView(
                pubId,
                "com.crosspaynet.compliance.CompliancePaymentListener",
                "PaymentInstructionReceived",
                pubDate,
                3,
                Optional.of(pubDate.plus(1, ChronoUnit.MINUTES))
        );

        when(eventPublicationQueryPort.findIncompletePublications(any(), any(), eq(50)))
                .thenReturn(new EventPublicationPage(List.of(view), false, Optional.empty()));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(1);
        assertThat(outcome.discrepanciesFound()).isEqualTo(1);

        verify(discrepancyRecorder).recordDiscrepancy(
                eq(runId),
                eq(DiscrepancyType.INCOMPLETE_EVENT_PUBLICATION),
                eq(pubId.toString()),
                org.mockito.ArgumentMatchers.contains("Event publication incomplete for listener"),
                org.mockito.ArgumentMatchers.contains("\"completionAttempts\":3"),
                eq(now)
        );
    }

    @Test
    void shouldTraversePagesUntilQueryCompletes() {
        ReconciliationRunId runId = ReconciliationRunId.generate();
        Instant now = Instant.now();

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Instant t1 = now.minus(30, ChronoUnit.MINUTES);
        Instant t2 = now.minus(25, ChronoUnit.MINUTES);

        StalePublicationView v1 = new StalePublicationView(id1, "listener1", "Event1", t1, 1, Optional.empty());
        StalePublicationView v2 = new StalePublicationView(id2, "listener2", "Event2", t2, 2, Optional.empty());

        EventPublicationCursor cursor1 = new EventPublicationCursor(t1, id1);

        when(eventPublicationQueryPort.findIncompletePublications(any(), eq(null), eq(50)))
                .thenReturn(new EventPublicationPage(List.of(v1), true, Optional.of(cursor1)));

        when(eventPublicationQueryPort.findIncompletePublications(any(), eq(cursor1), eq(50)))
                .thenReturn(new EventPublicationPage(List.of(v2), false, Optional.empty()));

        CheckExecutionOutcome outcome = check.execute(runId, now);

        assertThat(outcome.status()).isEqualTo(ReconciliationCheckStatus.COMPLETED);
        assertThat(outcome.itemsScanned()).isEqualTo(2);
        assertThat(outcome.discrepanciesFound()).isEqualTo(2);
    }
}
