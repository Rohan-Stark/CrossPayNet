package com.crosspaynet.compliance;

import com.crosspaynet.compliance.contracts.ComplianceService;
import com.crosspaynet.compliance.contracts.ScreenPaymentCommand;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentInstructionReceived;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Objects;

@Component
public class CompliancePaymentListener {

    private static final Logger log = LoggerFactory.getLogger(CompliancePaymentListener.class);

    private static final PublicationTargetIdentifier TARGET_IDENTIFIER;

    static {
        try {
            Method method = CompliancePaymentListener.class.getMethod(
                    "onPaymentInstructionReceived",
                    PaymentInstructionReceived.class
            );
            String listenerId = new TransactionalApplicationListenerMethodAdapter(
                    "compliancePaymentListener",
                    CompliancePaymentListener.class,
                    method
            ).getListenerId();
            TARGET_IDENTIFIER = PublicationTargetIdentifier.of(listenerId);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Failed to resolve CompliancePaymentListener method identifier", ex);
        }
    }

    private final ComplianceService complianceService;

    public CompliancePaymentListener(ComplianceService complianceService) {
        this.complianceService = Objects.requireNonNull(complianceService, "complianceService cannot be null.");
    }

    public static PublicationTargetIdentifier getTargetIdentifier() {
        return TARGET_IDENTIFIER;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPaymentInstructionReceived(PaymentInstructionReceived event) {
        log.info("CompliancePaymentListener received PaymentInstructionReceived for paymentId: {}", event.paymentId());
        try {
            complianceService.screenPayment(new ScreenPaymentCommand(PaymentId.of(event.paymentId())));
        } catch (Exception ex) {
            log.error("Compliance screening failed for paymentId: {}. Payment remains in COMPLIANCE_HOLD for recovery.",
                    event.paymentId(), ex);
            throw ex;
        }
    }
}
