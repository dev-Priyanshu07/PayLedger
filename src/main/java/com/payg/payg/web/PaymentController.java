package com.payg.payg.web;

import com.payg.payg.dto.CreatePaymentRequest;
import com.payg.payg.dto.Payment;
import com.payg.payg.dto.PaymentDetails;
import com.payg.payg.security.ApiKeyFilter;
import com.payg.payg.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
@Validated
@AllArgsConstructor
public class PaymentController {

    private final PaymentService payments;

    /**
     * Accepts the intent to charge. Processing happens asynchronously, so this
     * returns {@code 202} - including on a replay, where it returns the
     * original payment unchanged whatever its current state.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Payment create(
            @RequestAttribute(ApiKeyFilter.MERCHANT_ID_ATTRIBUTE) String merchantId,
            @RequestHeader("Idempotency-Key") 
            @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        return payments.create(merchantId, idempotencyKey, request);
    }

    @GetMapping("/{id}")
    public PaymentDetails get(
            @RequestAttribute(ApiKeyFilter.MERCHANT_ID_ATTRIBUTE) String merchantId,
            @PathVariable UUID id) {

        return payments.get(merchantId, id);
    }
}
