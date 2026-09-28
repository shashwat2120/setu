package com.setu.finance.infrastructure;

import com.setu.finance.domain.StudentAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface StudentAccountRepository extends JpaRepository<StudentAccount, UUID> {

    Optional<StudentAccount> findByTenantIdAndStudentId(UUID tenantId, UUID studentId);

    /**
     * The ONLY way {@code balance} is ever mutated — a single atomic SQL
     * increment, never a Java read-modify-{@code save()}. This is
     * inherently race-free under real concurrent execution (PostgreSQL
     * serializes row-level UPDATEs), so no {@code @Version} column or
     * {@code SELECT ... FOR UPDATE} is needed here — a positive {@code
     * delta} records a new charge (balance owed increases), a negative one
     * records a successful payment (balance owed decreases).
     */
    @Modifying
    @Query("update StudentAccount a set a.balance = a.balance + :delta where a.id = :id")
    void adjustBalance(@Param("id") UUID id, @Param("delta") BigDecimal delta);
}
