package com.crosspaynet.compliance;

import com.crosspaynet.compliance.contracts.ComplianceDecision;
import com.crosspaynet.compliance.contracts.ComplianceNotFoundException;
import com.crosspaynet.compliance.contracts.ComplianceReviewId;
import com.crosspaynet.compliance.contracts.ComplianceReviewView;
import com.crosspaynet.compliance.contracts.ComplianceService;
import com.crosspaynet.compliance.contracts.ComplianceStatus;
import com.crosspaynet.compliance.contracts.ScreenPaymentCommand;
import com.crosspaynet.compliance.contracts.SubmitComplianceDecisionCommand;
import com.crosspaynet.compliance.domain.ComplianceReview;
import com.crosspaynet.compliance.domain.SanctionsMatcher;
import com.crosspaynet.compliance.infrastructure.persistence.ComplianceReviewEntity;
import com.crosspaynet.compliance.infrastructure.persistence.ComplianceReviewMapper;
import com.crosspaynet.compliance.infrastructure.persistence.ComplianceReviewRepository;
import com.crosspaynet.payment.PaymentService;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentReasonCode;
import com.crosspaynet.payment.contracts.PaymentView;
import com.crosspaynet.payment.contracts.RejectPaymentCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

@Service
public class ComplianceServiceImpl implements ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceServiceImpl.class);

    private final ComplianceReviewRepository reviewRepository;
    private final PaymentService paymentService;
    private final SanctionsMatcher sanctionsMatcher;

    @Autowired
    public ComplianceServiceImpl(
            ComplianceReviewRepository reviewRepository,
            PaymentService paymentService
    ) {
        this(reviewRepository, paymentService, new SanctionsMatcher());
    }

    public ComplianceServiceImpl(
            ComplianceReviewRepository reviewRepository,
            PaymentService paymentService,
            SanctionsMatcher sanctionsMatcher
    ) {
        this.reviewRepository = Objects.requireNonNull(reviewRepository, "reviewRepository cannot be null.");
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService cannot be null.");
        this.sanctionsMatcher = Objects.requireNonNull(sanctionsMatcher, "sanctionsMatcher cannot be null.");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ComplianceReviewView screenPayment(ScreenPaymentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("ScreenPaymentCommand cannot be null.");
        }

        // Idempotency check: if review already exists for this payment, return it immediately
        Optional<ComplianceReviewEntity> existing = reviewRepository.findByPaymentId(command.paymentId().getValue());
        if (existing.isPresent()) {
            return ComplianceReviewMapper.toDomain(existing.get()).toView();
        }

        PaymentView payment = paymentService.findPayment(command.paymentId())
                .orElseThrow(() -> new ComplianceNotFoundException("Payment not found for screening: " + command.paymentId()));

        SanctionsMatcher.ScreeningResult result = sanctionsMatcher.screen(payment.instruction());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ComplianceReviewId reviewId = ComplianceReviewId.generate();

        if (result.hasFlags()) {
            ComplianceReview pendingReview = ComplianceReview.createPending(
                    reviewId,
                    command.paymentId(),
                    result.riskScore(),
                    result.flagReasons(),
                    now
            );

            int inserted = reviewRepository.insertClaimOnConflictDoNothing(
                    reviewId.getValue(),
                    command.paymentId().getValue(),
                    pendingReview.getStatus().name(),
                    pendingReview.getRiskScore(),
                    ComplianceReviewMapper.serializeFlagReasons(pendingReview.getFlagReasons()),
                    null,
                    null,
                    null,
                    null,
                    now,
                    now
            );

            if (inserted == 0) {
                ComplianceReviewEntity conflictEntity = reviewRepository.findByPaymentId(command.paymentId().getValue())
                        .orElseThrow();
                return ComplianceReviewMapper.toDomain(conflictEntity).toView();
            }

            log.info("Compliance review created in PENDING hold for paymentId: {}, riskScore: {}",
                    command.paymentId(), result.riskScore());
            return pendingReview.toView();
        } else {
            ComplianceReview clearedReview = ComplianceReview.createAutoCleared(reviewId, command.paymentId(), now);

            int inserted = reviewRepository.insertClaimOnConflictDoNothing(
                    reviewId.getValue(),
                    command.paymentId().getValue(),
                    clearedReview.getStatus().name(),
                    clearedReview.getRiskScore(),
                    "",
                    clearedReview.getReviewerId(),
                    clearedReview.getDecision().name(),
                    clearedReview.getJustification(),
                    clearedReview.getDecidedAt(),
                    now,
                    now
            );

            if (inserted == 0) {
                ComplianceReviewEntity conflictEntity = reviewRepository.findByPaymentId(command.paymentId().getValue())
                        .orElseThrow();
                return ComplianceReviewMapper.toDomain(conflictEntity).toView();
            }

            paymentService.approveCompliance(command.paymentId());
            log.info("Compliance automated screening CLEARED for paymentId: {}", command.paymentId());
            return clearedReview.toView();
        }
    }

    @Override
    @PreAuthorize("hasRole('COMPLIANCE')")
    @Transactional(propagation = Propagation.REQUIRED)
    public ComplianceReviewView submitDecision(SubmitComplianceDecisionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("SubmitComplianceDecisionCommand cannot be null.");
        }

        ComplianceReviewEntity entity = reviewRepository.findById(command.reviewId().getValue())
                .orElseThrow(() -> new ComplianceNotFoundException("Compliance review not found: " + command.reviewId()));

        ComplianceReview domain = ComplianceReviewMapper.toDomain(entity);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        if (command.decision() == ComplianceDecision.RELEASE) {
            boolean wasPending = (domain.getStatus() == ComplianceStatus.PENDING);
            domain.release(command.reviewerId(), command.justification(), now);
            if (wasPending) {
                ComplianceReviewMapper.updateEntity(domain, entity);
                entity = reviewRepository.saveAndFlush(entity);

                paymentService.approveCompliance(domain.getPaymentId());
                log.info("Compliance officer '{}' approved RELEASE for reviewId: {}, paymentId: {}",
                        command.reviewerId(), command.reviewId(), domain.getPaymentId());
            }
            return ComplianceReviewMapper.toDomain(entity).toView();
        } else if (command.decision() == ComplianceDecision.REJECT) {
            boolean wasPending = (domain.getStatus() == ComplianceStatus.PENDING);
            domain.reject(command.reviewerId(), command.justification(), now);
            if (wasPending) {
                ComplianceReviewMapper.updateEntity(domain, entity);
                entity = reviewRepository.saveAndFlush(entity);

                paymentService.rejectPayment(new RejectPaymentCommand(
                        domain.getPaymentId(),
                        PaymentReasonCode.COMPLIANCE_REJECTED,
                        command.justification()
                ));
                log.info("Compliance officer '{}' submitted REJECT for reviewId: {}, paymentId: {}",
                        command.reviewerId(), command.reviewId(), domain.getPaymentId());
            }
            return ComplianceReviewMapper.toDomain(entity).toView();
        }

        throw new IllegalArgumentException("Unsupported compliance decision: " + command.decision());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComplianceReviewView> findReviewByPaymentId(PaymentId paymentId) {
        if (paymentId == null) {
            return Optional.empty();
        }
        return reviewRepository.findByPaymentId(paymentId.getValue())
                .map(ComplianceReviewMapper::toDomain)
                .map(ComplianceReview::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComplianceReviewView> findReviewById(ComplianceReviewId reviewId) {
        if (reviewId == null) {
            return Optional.empty();
        }
        return reviewRepository.findById(reviewId.getValue())
                .map(ComplianceReviewMapper::toDomain)
                .map(ComplianceReview::toView);
    }
}
