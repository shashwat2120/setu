package com.setu.finance.infrastructure;

import com.setu.finance.domain.StudentCharge;
import com.setu.finance.domain.StudentChargeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StudentChargeRepository extends JpaRepository<StudentCharge, UUID> {

    List<StudentCharge> findByStudentId(UUID studentId);

    /**
     * Status-checked conditional UPDATE — this entity has no {@code
     * @Version} column (none exists in the V43 schema, and Day 12 does not
     * add one), so the expected CURRENT status itself is the concurrency
     * guard: two concurrent transition attempts on the same row can never
     * both succeed, since only the first commits the row still matching
     * {@code expectedStatus}. Returns affected row count — 0 means either
     * the row doesn't exist or someone else already transitioned it.
     */
    @Modifying
    @Query("update StudentCharge c set c.status = :target where c.id = :id and c.status = :expected")
    int transitionIfStatus(@Param("id") UUID id, @Param("expected") StudentChargeStatus expected,
                            @Param("target") StudentChargeStatus target);
}
