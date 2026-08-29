package com.payg.payg.web;

import com.payg.payg.dto.GatewayAttempt;
import com.payg.payg.dto.PaymentDetails;
import com.payg.payg.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock private PaymentService payments;

    private PaymentController controller;

    @BeforeEach
    void setUp() {
        controller = new PaymentController(payments);
    }

    @Test
    void getDelegatesToServiceWithAuthenticatedMerchant() {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        PaymentDetails expected = new PaymentDetails(
                paymentId,
                orderId,
                "merchant_a",
                "idem-key",
                "hash",
                "cust_1",
                "SUCCESS",
                now.minusSeconds(10),
                now,
                List.of(new GatewayAttempt(
                        UUID.randomUUID(),
                        "mock-gateway",
                        "SUCCESS",
                        "gw_ref_1",
                        "ok",
                        now.minusSeconds(2),
                        now.minusSeconds(1))));

        when(payments.get("merchant_a", paymentId)).thenReturn(expected);

        PaymentDetails actual = controller.get("merchant_a", paymentId);

        assertThat(actual).isEqualTo(expected);
        verify(payments).get("merchant_a", paymentId);
    }
}
