package com.crosspaynet.operations.application.check;

import com.crosspaynet.events.contracts.EventPublicationCursor;
import com.crosspaynet.events.contracts.EventPublicationPage;
import com.crosspaynet.events.contracts.EventPublicationQueryPort;
import com.crosspaynet.events.contracts.StalePublicationView;
import com.crosspaynet.operations.domain.DiscrepancyType;
import com.crosspaynet.operations.domain.ReconciliationRunId;
import com.crosspaynet.operations.infrastructure.configuration.OperationsReconciliationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Check D: Scans incomplete transactional event publications older than the configured threshold.
 */
@Component
public class IncompleteEventPublicationCheck implements ReconciliationCheckExecutor {

    public static final String CHECK_NAME = "INCOMPLETE_EVENT_PUBLICATION_CHECK";

    private final EventPublicationQueryPort eventPublicationQueryPort;
    private final DiscrepancyRecorder discrepancyRecorder;
    private final OperationsReconciliationProperties properties;

    public IncompleteEventPublicationCheck(
            EventPublicationQueryPort eventPublicationQueryPort,
            DiscrepancyRecorder discrepancyRecorder,
            OperationsReconciliationProperties properties
    ) {
        this.eventPublicationQueryPort = Objects.requireNonNull(eventPublicationQueryPort, "eventPublicationQueryPort cannot be null.");
        this.discrepancyRecorder = Objects.requireNonNull(discrepancyRecorder, "discrepancyRecorder cannot be null.");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null.");
    }

    @Override
    public String getCheckName() {
        return CHECK_NAME;
    }

    @Override
    public CheckExecutionOutcome execute(ReconciliationRunId runId, Instant runStartedAt) {
        Instant cutoff = runStartedAt.minus(properties.getStaleEventPublicationThreshold());
        int itemsScanned = 0;
        int discrepanciesFound = 0;
        EventPublicationCursor cursor = null;
        boolean hasMore = true;

        try {
            while (hasMore) {
                EventPublicationPage page = eventPublicationQueryPort.findIncompletePublications(
                        cutoff,
                        cursor,
                        properties.getPageSize()
                );

                for (StalePublicationView pub : page.items()) {
                    itemsScanned++;
                    discrepanciesFound++;
                    recordStalePublication(runId, pub, runStartedAt);
                }

                hasMore = page.hasMore() && page.nextCursor().isPresent();
                cursor = page.nextCursor().orElse(null);
            }

            return CheckExecutionOutcome.success(CHECK_NAME, itemsScanned, discrepanciesFound);
        } catch (Exception ex) {
            String errorSummary = sanitizeException(ex);
            return CheckExecutionOutcome.failure(CHECK_NAME, itemsScanned, discrepanciesFound, errorSummary);
        }
    }

    private void recordStalePublication(ReconciliationRunId runId, StalePublicationView pub, Instant observedAt) {
        String entityId = pub.publicationId().toString();
        String summary = String.format(
                "Event publication incomplete for listener '%s' and event '%s' (published at %s, attempts=%d)",
                pub.listenerId(), pub.eventType(), pub.publicationDate(), pub.completionAttempts()
        );

        StringBuilder details = new StringBuilder("{");
        details.append("\"publicationId\":\"").append(entityId).append("\",");
        details.append("\"listenerId\":\"").append(escapeJson(pub.listenerId())).append("\",");
        details.append("\"eventType\":\"").append(escapeJson(pub.eventType())).append("\",");
        details.append("\"publicationDate\":\"").append(pub.publicationDate()).append("\",");
        details.append("\"completionAttempts\":").append(pub.completionAttempts());
        pub.lastResubmissionDate().ifPresent(resubDate ->
                details.append(",\"lastResubmissionDate\":\"").append(resubDate).append("\"")
        );
        details.append("}");

        discrepancyRecorder.recordDiscrepancy(
                runId,
                DiscrepancyType.INCOMPLETE_EVENT_PUBLICATION,
                entityId,
                summary,
                details.toString(),
                observedAt
        );
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    private static String sanitizeException(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName();
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
