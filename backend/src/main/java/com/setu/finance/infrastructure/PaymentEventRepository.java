package com.setu.finance.infrastructure;

import com.setu.finance.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    Optional<PaymentEvent> findByProviderEventId(String providerEventId);
}
