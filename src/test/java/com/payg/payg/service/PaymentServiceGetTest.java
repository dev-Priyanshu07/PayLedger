package com.payg.payg.service;

import com.payg.payg.dto.PaymentDetails;
import com.payg.payg.entity.GatewayAttemptEntity;
import com.payg.payg.entity.PaymentEntity;
import com.payg.payg.gateway.PaymentGateway;
import com.payg.payg.repository.GatewayAttemptRepository;
import com.payg.payg.repository.OrderRepository;
import com.payg.payg.repository.PaymentRepository;
import com.payg.payg.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceGetTest {

    @Mock private PaymentRepository payments;
    @Mock private OrderRepository orders;
    @Mock private GatewayAttemptRepository attempts;
    @Mock private PaymentGateway gateway;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(payments, orders, attempts, gateway);
    }

    @Test
    void returnsPaymentWithAttemptHistoryForTheMerchant() {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        PaymentEntity payment = new PaymentEntity(
                paymentId, orderId, "merchant_a", "idem-key", "hash",
                "cust_1", "SUCCESS", now.minusSeconds(10), now,
                null, null);
        GatewayAttemptEntity attempt = new GatewayAttemptEntity(
                UUID.randomUUID(), paymentId, "mock-gateway", "SUCCESS",
                "gw_ref_1", "ok", now.minusSeconds(2), now.minusSeconds(1));

        when(payments.findByIdAndMerchantId(paymentId, "merchant_a"))
                .thenReturn(Optional.of(payment));
        when(attempts.findByPaymentIdOrderByStartedAt(paymentId))
                .thenReturn(List.of(attempt));

        PaymentDetails details = service.get("merchant_a", paymentId);

        assertThat(details.id()).isEqualTo(paymentId);
        assertThat(details.orderId()).isEqualTo(orderId);
        assertThat(details.merchantId()).isEqualTo("merchant_a");
        assertThat(details.status()).isEqualTo("SUCCESS");
        assertThat(details.attempts()).hasSize(1);
        assertThat(details.attempts().getFirst().gatewayName()).isEqualTo("mock-gateway");
        assertThat(details.attempts().getFirst().outcome()).isEqualTo("SUCCESS");
        assertThat(details.attempts().getFirst().gatewayRef()).isEqualTo("gw_ref_1");
    }

    @Test
    void rejectsPaymentsThatDoNotBelongToTheMerchant() {
        UUID paymentId = UUID.randomUUID();
        when(payments.findByIdAndMerchantId(paymentId, "merchant_b"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("merchant_b", paymentId))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiError = (ApiException) error;
                    assertThat(apiError.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiError.code()).isEqualTo("payment_not_found");
                });
    }
}
