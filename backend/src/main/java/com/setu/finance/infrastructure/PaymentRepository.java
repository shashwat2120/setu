package com.setu.finance.infrastructure;

import com.setu.finance.domain.Payment;
import com.setu.finance.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByStudentAccountId(UUID studentAccountId);

    /** Status-checked conditional UPDATE — see StudentChargeRepository#transitionIfStatus's javadoc for why this replaces a @Version column here. */
    @Modifying
    @Query("update Payment p set p.status = :target where p.id = :id and p.status = :expected")
    int transitionIfStatus(@Param("id") UUID id, @Param("expected") PaymentStatus expected, @Param("target") PaymentStatus target);
}
