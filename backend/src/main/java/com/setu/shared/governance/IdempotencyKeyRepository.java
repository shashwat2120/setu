package com.setu.shared.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByKeyAndScope(String key, String scope);

    /** Status-checked conditional UPDATE (this project's established idiom, e.g. AssessmentResponseRepository#updateAnswerIfVersionMatches): only completes a row still PENDING, so a concurrent double-complete can never happen. Returns affected row count. */
    @Modifying
    @Query("update IdempotencyKey k set k.status = com.setu.shared.governance.IdempotencyKey.Status.COMPLETED, k.resultJson = :resultJson "
        + "where k.id = :id and k.status = com.setu.shared.governance.IdempotencyKey.Status.PENDING")
    int completeIfPending(@Param("id") UUID id, @Param("resultJson") String resultJson);
}
