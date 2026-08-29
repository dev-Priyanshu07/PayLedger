package com.payg.payg.service;

import com.payg.payg.dto.CreateOrderRequest;
import com.payg.payg.dto.Order;
import com.payg.payg.entity.OrderEntity;
import com.payg.payg.repository.OrderRepository;
import com.payg.payg.web.ApiException;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@AllArgsConstructor
public class OrderService {

    /** Whether this call created the row, or found one an earlier call created. */
    public record Result(Order order, boolean created) {
    }

    private final OrderRepository orders;

    /**
     * Creates an order, or returns the existing one if this merchant order id
     * has been seen before.
     *
     * <p>Insert first and let {@code uq_orders_merchant_order} arbitrate. A
     * check-then-insert would leave a window in which two concurrent requests
     * both see nothing and both insert; the database has no such window.
     *
     * <p>Deliberately not {@code @Transactional}: the constraint violation must
     * surface as a caught exception, and the follow-up read must run in a
     * transaction that is not already marked rollback-only.
     */
    public Result create(String merchantId, CreateOrderRequest request) {
        OrderEntity entity = new OrderEntity(
                UUID.randomUUID(),
                merchantId,
                request.merchantOrderId(),
                request.amountMinor(),
                request.currency(),
                OffsetDateTime.now());

        try {
            return new Result(toDto(orders.saveAndFlush(entity)), true);
        } catch (DataIntegrityViolationException collision) {
            OrderEntity existing = orders
                    .findByMerchantIdAndMerchantOrderId(merchantId, request.merchantOrderId())
                    .orElseThrow(() -> collision);
            return new Result(toDto(existing), false);
        }
    }

    public Order get(String merchantId, UUID id) {
        return orders.findByIdAndMerchantId(id, merchantId)
                .map(OrderService::toDto)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "order_not_found", "No such order: " + id));
    }

    static Order toDto(OrderEntity e) {
        return new Order(
                e.getId(),
                e.getMerchantId(),
                e.getMerchantOrderId(),
                e.getAmountMinor(),
                e.getCurrency(),
                e.getCreatedAt());
    }
}
