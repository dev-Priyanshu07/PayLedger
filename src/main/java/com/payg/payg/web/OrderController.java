package com.payg.payg.web;

import com.payg.payg.dto.CreateOrderRequest;
import com.payg.payg.dto.Order;
import com.payg.payg.security.ApiKeyFilter;
import com.payg.payg.service.OrderService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@AllArgsConstructor
public class OrderController {

    private final OrderService orders;

    /**
     * Declares what a merchant order is worth.
     *
     * <p>{@code 201} on first call, {@code 200} on a replay of the same
     * {@code merchantOrderId} - the body is identical either way, so a merchant
     * that ignores the distinction still behaves correctly.
     */
    @PostMapping
    public ResponseEntity<Order> create(
            @RequestAttribute(ApiKeyFilter.MERCHANT_ID_ATTRIBUTE) String merchantId,
            @Valid @RequestBody CreateOrderRequest request) {

        OrderService.Result result = orders.create(merchantId, request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.order());
    }

    @GetMapping("/{id}")
    public Order get(
            @RequestAttribute(ApiKeyFilter.MERCHANT_ID_ATTRIBUTE) String merchantId,
            @PathVariable UUID id) {

        return orders.get(merchantId, id);
    }
}
