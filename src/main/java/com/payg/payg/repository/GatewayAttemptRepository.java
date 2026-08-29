package com.payg.payg.repository;

import com.payg.payg.entity.GatewayAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayAttemptRepository extends JpaRepository<GatewayAttemptEntity, UUID> {

    List<GatewayAttemptEntity> findByPaymentIdOrderByStartedAt(UUID paymentId);

    Optional<GatewayAttemptEntity> findTopByPaymentIdOrderByStartedAtDesc(UUID paymentId);
}
