package com.crosspaynet.payment;

import com.crosspaynet.banking.BankingService;
import com.crosspaynet.banking.contracts.AccountId;
import com.crosspaynet.common.money.Currency;
import com.crosspaynet.common.money.Money;
import com.crosspaynet.payment.contracts.PaymentId;
import com.crosspaynet.payment.contracts.PaymentReconciliationCursor;
import com.crosspaynet.payment.contracts.PaymentReconciliationPage;
import com.crosspaynet.payment.contracts.PaymentReconciliationRecord;
import com.crosspaynet.payment.contracts.PaymentStatus;
import com.crosspaynet.payment.contracts.PaymentValidationException;
import com.crosspaynet.payment.infrastructure.persistence.PaymentEntity;
import com.crosspaynet.payment.infrastructure.persistence.PaymentMapper;
import com.crosspaynet.payment.infrastructure.persistence.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentReconciliationPortUnitTest {

    private PaymentRepository paymentRepository;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        PaymentMapper paymentMapper = mock(PaymentMapper.class);
        BankingService bankingService = mock(BankingService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        paymentService = new PaymentService(paymentRepository, paymentMapper, bankingService, eventPublisher);
    }

    @Test
    void shouldFetchFirstPageAndComputeContinuationCursor() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID pid1 = UUID.randomUUID();
        UUID pid2 = UUID.randomUUID();
        UUID pid3 = UUID.randomUUID();

        PaymentEntity e1 = createEntity(pid1, now.minusSeconds(30), PaymentStatus.PENDING_EXTERNAL);
        PaymentEntity e2 = createEntity(pid2, now.minusSeconds(20), PaymentStatus.PENDING_EXTERNAL);
        PaymentEntity e3 = createEntity(pid3, now.minusSeconds(10), PaymentStatus.PENDING_EXTERNAL);

        when(paymentRepository.findForReconciliationFirstPage(
                eq(Set.of(PaymentStatus.PENDING_EXTERNAL)),
                eq(now),
                any(PageRequest.class)
        )).thenReturn(List.of(e1, e2, e3));

        PaymentReconciliationPage page = paymentService.findPaymentsForReconciliation(
                Set.of(PaymentStatus.PENDING_EXTERNAL),
                now,
                null,
                2
        );

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isPresent();
        PaymentReconciliationCursor cursor = page.nextCursor().get();
        assertThat(cursor.paymentId()).isEqualTo(PaymentId.of(pid2));
        assertThat(cursor.updatedAt()).isEqualTo(e2.getUpdatedAt());

        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(paymentRepository).findForReconciliationFirstPage(any(), any(), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void shouldFetchNextPageWithCursorAndIndicateEndOfScan() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID pid2 = UUID.randomUUID();
        UUID pid3 = UUID.randomUUID();

        PaymentEntity e3 = createEntity(pid3, now.minusSeconds(10), PaymentStatus.PENDING_EXTERNAL);
        PaymentReconciliationCursor cursor = new PaymentReconciliationCursor(now.minusSeconds(20), PaymentId.of(pid2));

        when(paymentRepository.findForReconciliationNextPage(
                eq(Set.of(PaymentStatus.PENDING_EXTERNAL)),
                eq(now),
                eq(cursor.updatedAt()),
                eq(pid2),
                any(PageRequest.class)
        )).thenReturn(List.of(e3));

        PaymentReconciliationPage page = paymentService.findPaymentsForReconciliation(
                Set.of(PaymentStatus.PENDING_EXTERNAL),
                now,
                cursor,
                2
        );

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.items().get(0).paymentId()).isEqualTo(PaymentId.of(pid3));
    }

    @Test
    void shouldCapLimitAtMaximumPageSize() {
        Instant now = Instant.now();
        when(paymentRepository.findForReconciliationFirstPage(any(), any(), any()))
                .thenReturn(List.of());

        paymentService.findPaymentsForReconciliation(
                Set.of(PaymentStatus.EXECUTING),
                now,
                null,
                1000
        );

        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(paymentRepository).findForReconciliationFirstPage(any(), any(), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(501);
    }

    @Test
    void shouldEnforceInputValidation() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> paymentService.findPaymentsForReconciliation(null, now, null, 10))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("statuses cannot be null or empty");

        assertThatThrownBy(() -> paymentService.findPaymentsForReconciliation(Set.of(), now, null, 10))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("statuses cannot be null or empty");

        assertThatThrownBy(() -> paymentService.findPaymentsForReconciliation(Set.of(PaymentStatus.ROUTING), null, null, 10))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("cutoffTime cannot be null");

        assertThatThrownBy(() -> paymentService.findPaymentsForReconciliation(Set.of(PaymentStatus.ROUTING), now, null, 0))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("limit must be at least 1");
    }

    private PaymentEntity createEntity(UUID id, Instant updatedAt, PaymentStatus status) {
        return new PaymentEntity(
                id,
                UUID.randomUUID(),
                "REF-" + id.toString().substring(0, 8),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                new BigDecimal("100.0000"),
                "USD",
                "Beneficiary Name",
                "BEN-ACC-123",
                "DEUTDEFFXXX",
                status,
                UUID.randomUUID(),
                "ROUTE-1",
                "DISPATCH-1",
                null,
                null,
                null,
                updatedAt.minusSeconds(60),
                updatedAt
        );
    }
}
