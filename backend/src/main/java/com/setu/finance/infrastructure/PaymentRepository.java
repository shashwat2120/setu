package com.setu.finance.infrastructure;

import com.setu.finance.domain.Payment;
import com.setu.finance.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByStudentAccountId(UUID studentAccountId);

    Optional<Payment> findByProviderRef(String providerRef);

    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    /** The payment in flight or paid for a fee (at most one: uq_payments_open_per_charge). */
    @Query("select p from Payment p where p.studentChargeId = :chargeId and p.status in (com.setu.finance.domain.PaymentStatus.INITIATED, com.setu.finance.domain.PaymentStatus.SUCCEEDED)")
    Optional<Payment> findOpenForCharge(@Param("chargeId") UUID chargeId);

    /** Records the provider's order once: a concurrent second checkout finds 0 rows updated and uses the order that won. */
    @Modifying
    @Query("update Payment p set p.providerRef = :ref where p.id = :id and p.providerRef is null")
    int setProviderRefIfAbsent(@Param("id") UUID id, @Param("ref") String ref);

    @Modifying
    @Query("update Payment p set p.providerPaymentId = :providerPaymentId where p.id = :id and p.providerPaymentId is null")
    int setProviderPaymentIdIfAbsent(@Param("id") UUID id, @Param("providerPaymentId") String providerPaymentId);

    /** Status-checked conditional UPDATE — see StudentChargeRepository#transitionIfStatus's javadoc for why this replaces a @Version column here. */
    @Modifying
    @Query("update Payment p set p.status = :target where p.id = :id and p.status = :expected")
    int transitionIfStatus(@Param("id") UUID id, @Param("expected") PaymentStatus expected, @Param("target") PaymentStatus target);
}
