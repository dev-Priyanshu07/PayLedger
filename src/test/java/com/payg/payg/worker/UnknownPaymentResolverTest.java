package com.payg.payg.worker;

import com.payg.payg.entity.PaymentEntity;
import com.payg.payg.repository.PaymentRepository;
import com.payg.payg.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnknownPaymentResolverTest {

    @Mock private PaymentRepository payments;
    @Mock private PaymentService service;

    private UnknownPaymentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new UnknownPaymentResolver(payments, service);
    }

    @Test
    void resolvesEveryUnknownPaymentReturnedByTheRepository() {
        PaymentEntity first = unknownPayment();
        PaymentEntity second = unknownPayment();
        when(payments.findTop20ByStatusOrderByUpdatedAt("UNKNOWN"))
                .thenReturn(List.of(first, second));

        resolver.resolveUnknownPayments();

        verify(service).resolveUnknown(first.getId());
        verify(service).resolveUnknown(second.getId());
    }

    @Test
    void oneResolutionFailureDoesNotStopTheRestOfTheBatch() {
        PaymentEntity bad = unknownPayment();
        PaymentEntity good = unknownPayment();
        when(payments.findTop20ByStatusOrderByUpdatedAt("UNKNOWN"))
                .thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("temporary failure"))
                .when(service).resolveUnknown(bad.getId());

        resolver.resolveUnknownPayments();

        verify(service).resolveUnknown(bad.getId());
        verify(service).resolveUnknown(good.getId());
    }

    private static PaymentEntity unknownPayment() {
        OffsetDateTime now = OffsetDateTime.now();
        return new PaymentEntity(
                UUID.randomUUID(), UUID.randomUUID(), "merchant_a", "idem-key",
                "hash", "cust_1", "UNKNOWN", now.minusSeconds(10), now,
                "worker-1", now.minusSeconds(1));
    }
}
