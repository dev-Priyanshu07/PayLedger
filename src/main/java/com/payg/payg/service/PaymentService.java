package com.payg.payg.service;

import com.payg.payg.dto.CreatePaymentRequest;
import com.payg.payg.dto.Payment;
import com.payg.payg.entity.GatewayAttemptEntity;
import com.payg.payg.entity.OrderEntity;
import com.payg.payg.entity.PaymentEntity;
import com.payg.payg.gateway.ChargeRequest;
import com.payg.payg.gateway.GatewayOutcome;
import com.payg.payg.gateway.PaymentGateway;
import com.payg.payg.repository.GatewayAttemptRepository;
import com.payg.payg.repository.OrderRepository;
import com.payg.payg.repository.PaymentRepository;
import com.payg.payg.web.ApiException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Slf4j
@AllArgsConstructor
public class PaymentService {

    private static final String INITIATED = "INITIATED";
    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String UNKNOWN = "UNKNOWN";

    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final GatewayAttemptRepository attempts;
    private final PaymentGateway gateway;

    /**
     * Records the intent to charge. Returns the existing payment, unchanged and
     * whatever its state, if this idempotency key has been seen before.
     *
     * <p>Insert first and let {@code uq_payments_idempotency} arbitrate - see
     * {@link OrderService#create} for why, and for why this is not
     * {@code @Transactional}.
     *
     * <p>A replayed key whose body differs is a merchant bug, not a retry.
     * Honouring it would either charge the wrong thing or silently ignore what
     * was asked for, so it is rejected. {@code request_hash} is what makes the
     * two cases distinguishable.
     */
    public Payment create(String merchantId, String idempotencyKey, CreatePaymentRequest request) {
        // Checked up front so a bad order id yields a clear 404 rather than a
        // foreign key violation. Scoped by merchant: another merchant's order
        // must be indistinguishable from one that does not exist.
        OrderEntity order = orders.findByIdAndMerchantId(request.orderId(), merchantId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "order_not_found",
                        "No such order: " + request.orderId()));

        String requestHash = hash(request);
        OffsetDateTime now = OffsetDateTime.now();

        PaymentEntity entity = new PaymentEntity(
                UUID.randomUUID(),
                request.orderId(),
                merchantId,
                idempotencyKey,
                requestHash,
                request.customerRef(),
                INITIATED,
                now,
                now,
                null,
                null);

        PaymentEntity persisted;
        try {
            persisted = payments.saveAndFlush(entity);
        } catch (DataIntegrityViolationException collision) {
            PaymentEntity existing = payments
                    .findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .orElseThrow(() -> collision);

            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
                        "Idempotency-Key '" + idempotencyKey
                                + "' was already used for a different request.");
            }
            // A replay. Return what the original call produced and charge
            // nothing - reaching the gateway from here is exactly the
            // double-charge this whole mechanism exists to prevent.
            return toDto(existing);
        }

        // Only ever reached by the one caller whose insert won. Charging
        // happens later, off a worker's claim (see PaymentWorker) - this
        // method's job ends at recording the intent durably (N1).
        return toDto(persisted);
    }

    /**
     * Takes an already-claimed payment to the gateway and records what
     * happened.
     *
     * <p>Called by {@code PaymentWorker} after {@link
     * com.payg.payg.repository.PaymentRepository#claimNext} has already moved
     * the row from {@code INITIATED} to {@code PROCESSING} and taken a lease
     * on it - so unlike {@link #create}, there is no transition into
     * {@code PROCESSING} here, only out of it.
     *
     * <p>Both entities are re-fetched by id rather than passed in, because the
     * worker running this did not necessarily create them - it only knows the
     * id of a row it just claimed.
     */
    public void processClaimed(UUID paymentId) {
        PaymentEntity payment = payments.findById(paymentId).orElseThrow();
        OrderEntity order = orders.findById(payment.getOrderId()).orElseThrow();
        String gatewayRef = gatewayReference(payment.getId());

        OffsetDateTime startedAt = OffsetDateTime.now();
        GatewayOutcome outcome = call(payment, order, gatewayRef);
        OffsetDateTime completedAt = OffsetDateTime.now();

        String recordedGatewayRef = outcome.gatewayRef() == null ? gatewayRef : outcome.gatewayRef();
        attempts.save(new GatewayAttemptEntity(
                UUID.randomUUID(),
                payment.getId(),
                gateway.name(),
                outcome.result().name(),
                recordedGatewayRef,
                outcome.reason(),
                startedAt,
                completedAt));

        String resulting = switch (outcome.result()) {
            case SUCCESS -> SUCCESS;
            case DEFINITE_FAILURE -> FAILED;
            case INDETERMINATE -> UNKNOWN;
        };

        payments.transition(payment.getId(), PROCESSING, resulting, OffsetDateTime.now());

        log.info("payment={} gateway={} outcome={} ref={} status={}",
                payment.getId(), gateway.name(), outcome.result(),
                recordedGatewayRef, resulting);
    }

    /**
     * Re-checks an {@code UNKNOWN} payment using the same gateway reference as
     * the original uncertain call.
     *
     * <p>A definite gateway answer moves the payment to {@code SUCCESS} or
     * {@code FAILED}. Another indeterminate answer leaves it {@code UNKNOWN}
     * for a later resolver tick.
     */
    public void resolveUnknown(UUID paymentId) {
        PaymentEntity payment = payments.findById(paymentId).orElseThrow();
        if (!UNKNOWN.equals(payment.getStatus())) {
            return;
        }

        GatewayAttemptEntity latestAttempt = attempts.findTopByPaymentIdOrderByStartedAtDesc(paymentId)
                .orElseThrow();
        String gatewayRef = latestAttempt.getGatewayRef();
        if (gatewayRef == null || gatewayRef.isBlank()) {
            log.warn("payment={} is UNKNOWN but has no gateway reference to resolve", paymentId);
            return;
        }

        OrderEntity order = orders.findById(payment.getOrderId()).orElseThrow();

        OffsetDateTime startedAt = OffsetDateTime.now();
        GatewayOutcome outcome = status(payment, order, gatewayRef);
        OffsetDateTime completedAt = OffsetDateTime.now();

        attempts.save(new GatewayAttemptEntity(
                UUID.randomUUID(),
                payment.getId(),
                gateway.name(),
                outcome.result().name(),
                gatewayRef,
                "status resolution: " + outcome.reason(),
                startedAt,
                completedAt));

        if (outcome.result() == GatewayOutcome.Result.INDETERMINATE) {
            log.info("payment={} gateway={} ref={} remains UNKNOWN",
                    payment.getId(), gateway.name(), gatewayRef);
            return;
        }

        String resulting = switch (outcome.result()) {
            case SUCCESS -> SUCCESS;
            case DEFINITE_FAILURE -> FAILED;
            case INDETERMINATE -> UNKNOWN;
        };

        payments.transition(payment.getId(), UNKNOWN, resulting, OffsetDateTime.now());

        log.info("payment={} gateway={} ref={} resolved status={}",
                payment.getId(), gateway.name(), gatewayRef, resulting);
    }

    /**
     * Invokes the gateway, converting a thrown exception into
     * {@code INDETERMINATE}.
     *
     * <p>An exception tells us the call did not complete, not that it did not
     * happen - the request may have been received and the response lost. The
     * only safe reading is that we do not know, which forbids failover. Calling
     * it a failure here would be the single easiest way to double-charge a
     * customer.
     */
    private GatewayOutcome call(PaymentEntity payment, OrderEntity order, String gatewayRef) {
        try {
            return gateway.charge(new ChargeRequest(
                    payment.getId(),
                    order.getAmountMinor(),
                    order.getCurrency(),
                    payment.getCustomerRef(),
                    gatewayRef));
        } catch (RuntimeException e) {
            log.warn("payment={} gateway={} threw; treating as indeterminate",
                    payment.getId(), gateway.name(), e);
            return GatewayOutcome.indeterminate(gatewayRef,
                    gateway.name() + " threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private GatewayOutcome status(PaymentEntity payment, OrderEntity order, String gatewayRef) {
        try {
            return gateway.status(new ChargeRequest(
                    payment.getId(),
                    order.getAmountMinor(),
                    order.getCurrency(),
                    payment.getCustomerRef(),
                    gatewayRef), gatewayRef);
        } catch (RuntimeException e) {
            log.warn("payment={} gateway={} status check threw; keeping UNKNOWN",
                    payment.getId(), gateway.name(), e);
            return GatewayOutcome.indeterminate(gatewayRef,
                    gateway.name() + " status threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String gatewayReference(UUID paymentId) {
        return "gw_" + paymentId.toString().replace("-", "");
    }

    /** Canonical fingerprint of the request body, for idempotent replay checks. */
    private static String hash(CreatePaymentRequest request) {
        String canonical = request.orderId() + "|" + request.customerRef();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    static Payment toDto(PaymentEntity e) {
        return new Payment(
                e.getId(),
                e.getOrderId(),
                e.getMerchantId(),
                e.getIdempotencyKey(),
                e.getRequestHash(),
                e.getCustomerRef(),
                e.getStatus(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
