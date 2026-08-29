package com.payg.payg.service;

import com.payg.payg.entity.GatewayAttemptEntity;
import com.payg.payg.entity.OrderEntity;
import com.payg.payg.entity.PaymentEntity;
import com.payg.payg.gateway.GatewayOutcome;
import com.payg.payg.gateway.PaymentGateway;
import com.payg.payg.repository.GatewayAttemptRepository;
import com.payg.payg.repository.OrderRepository;
import com.payg.payg.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2: {@link PaymentService#processClaimed} is what {@code PaymentWorker} now
 * calls in place of the old inline {@code charge()} path. These tests cover
 * exactly the three outcomes {@link GatewayOutcome.Result} can produce,
 * without a database - {@code processClaimed} re-fetches by id, so a mocked
 * repository returning a fixed entity is enough to exercise it.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceProcessClaimedTest {

    @Mock private PaymentRepository payments;
    @Mock private OrderRepository orders;
    @Mock private GatewayAttemptRepository attempts;
    @Mock private PaymentGateway gateway;

    private PaymentService service;

    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentService(payments, orders, attempts, gateway);

        // The row as PaymentWorker.claimNext would have left it: already
        // PROCESSING, already leased. processClaimed never re-derives this -
        // it only reads it back to reach the order and the customer ref.
        PaymentEntity claimedPayment = new PaymentEntity(
                PAYMENT_ID, ORDER_ID, "merchant_a", "idem-key", "hash",
                "cust_1", "PROCESSING", OffsetDateTime.now(), OffsetDateTime.now(),
                "worker-1", OffsetDateTime.now().plusSeconds(30));

        OrderEntity order = new OrderEntity(
                ORDER_ID, "merchant_a", "order-1", 50000L, "INR", OffsetDateTime.now());

        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(claimedPayment));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(gateway.name()).thenReturn("mock-gateway");
    }

    @Test
    void successOutcomeTransitionsToSuccessAndRecordsTheAttempt() {
        when(gateway.charge(any())).thenReturn(GatewayOutcome.success("gw_ref_1", "ok"));

        service.processClaimed(PAYMENT_ID);

        ArgumentCaptor<GatewayAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(GatewayAttemptEntity.class);
        verify(attempts).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getOutcome()).isEqualTo("SUCCESS");
        assertThat(attemptCaptor.getValue().getGatewayRef()).isEqualTo("gw_ref_1");

        verify(payments).transition(eq(PAYMENT_ID), eq("PROCESSING"), eq("SUCCESS"), any());
    }

    @Test
    void definiteFailureTransitionsToFailed() {
        when(gateway.charge(any())).thenReturn(GatewayOutcome.definiteFailure("card declined"));

        service.processClaimed(PAYMENT_ID);

        verify(payments).transition(eq(PAYMENT_ID), eq("PROCESSING"), eq("FAILED"), any());
    }

    @Test
    void indeterminateTransitionsToUnknown() {
        when(gateway.charge(any())).thenReturn(GatewayOutcome.indeterminate("timed out"));

        service.processClaimed(PAYMENT_ID);

        verify(payments).transition(eq(PAYMENT_ID), eq("PROCESSING"), eq("UNKNOWN"), any());
    }

    @Test
    void gatewayThrowingIsTreatedAsIndeterminateNotAsAnUncaughtFailure() {
        when(gateway.charge(any())).thenThrow(new RuntimeException("connection reset"));

        service.processClaimed(PAYMENT_ID);

        ArgumentCaptor<GatewayAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(GatewayAttemptEntity.class);
        verify(attempts).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getOutcome()).isEqualTo("INDETERMINATE");

        verify(payments).transition(eq(PAYMENT_ID), eq("PROCESSING"), eq("UNKNOWN"), any());
    }

    @Test
    void unknownPaymentCanBeResolvedToSuccess() {
        PaymentEntity unknownPayment = new PaymentEntity(
                PAYMENT_ID, ORDER_ID, "merchant_a", "idem-key", "hash",
                "cust_1", "UNKNOWN", OffsetDateTime.now(), OffsetDateTime.now(),
                "worker-1", OffsetDateTime.now().minusSeconds(1));
        GatewayAttemptEntity unknownAttempt = new GatewayAttemptEntity(
                UUID.randomUUID(), PAYMENT_ID, "mock-gateway", "INDETERMINATE",
                "gw_ref_1", "timed out", OffsetDateTime.now(), OffsetDateTime.now());

        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(unknownPayment));
        when(attempts.findTopByPaymentIdOrderByStartedAtDesc(PAYMENT_ID))
                .thenReturn(Optional.of(unknownAttempt));
        when(gateway.status(any(), eq("gw_ref_1")))
                .thenReturn(GatewayOutcome.success("gw_ref_1", "resolved"));

        service.resolveUnknown(PAYMENT_ID);

        verify(payments).transition(eq(PAYMENT_ID), eq("UNKNOWN"), eq("SUCCESS"), any());
    }

    @Test
    void stillIndeterminateStatusCheckLeavesPaymentUnknown() {
        PaymentEntity unknownPayment = new PaymentEntity(
                PAYMENT_ID, ORDER_ID, "merchant_a", "idem-key", "hash",
                "cust_1", "UNKNOWN", OffsetDateTime.now(), OffsetDateTime.now(),
                "worker-1", OffsetDateTime.now().minusSeconds(1));
        GatewayAttemptEntity unknownAttempt = new GatewayAttemptEntity(
                UUID.randomUUID(), PAYMENT_ID, "mock-gateway", "INDETERMINATE",
                "gw_ref_1", "timed out", OffsetDateTime.now(), OffsetDateTime.now());

        when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(unknownPayment));
        when(attempts.findTopByPaymentIdOrderByStartedAtDesc(PAYMENT_ID))
                .thenReturn(Optional.of(unknownAttempt));
        when(gateway.status(any(), eq("gw_ref_1")))
                .thenReturn(GatewayOutcome.indeterminate("gw_ref_1", "still unknown"));

        service.resolveUnknown(PAYMENT_ID);

        verify(payments, never()).transition(eq(PAYMENT_ID), eq("UNKNOWN"), any(), any());
    }
}
