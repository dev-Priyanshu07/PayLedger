package com.payg.payg.repository;

import com.payg.payg.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
// done
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByMerchantIdAndMerchantOrderId(String merchantId, String merchantOrderId);

    Optional<OrderEntity> findByIdAndMerchantId(UUID id, String merchantId);
}
