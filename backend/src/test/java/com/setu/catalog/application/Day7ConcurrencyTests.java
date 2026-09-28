package com.setu.catalog.application;

import com.setu.catalog.domain.Course;
import com.setu.catalog.infrastructure.CourseRepository;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 7 §18: prove the existing optimistic-locking-backed mutable
 * aggregates (entities with {@code @Version}, e.g. {@link Course}) don't
 * silently lose concurrent updates. This is NOT enrollment-capacity
 * concurrency (Day 8's scope) — it only verifies the generic JPA
 * optimistic-lock mechanism already present on every Day 7 versioned
 * entity actually fires. Deliberately not class-annotated
 * {@code @Transactional} (unlike the rest of this suite): true concurrent
 * commits require separate, independently-committed transactions, so each
 * step below uses an explicit {@code REQUIRES_NEW} transaction instead of
 * relying on the shared rolled-back test transaction.
 */
@SpringBootTest
class Day7ConcurrencyTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    CourseRepository courseRepository;
    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void concurrentCourseUpdatesDoNotSilentlyOverwriteEachOther() {
        var tenant = fixtures.provisionTenant("CONC1");
        UUID dept = fixtures.provisionDepartment(tenant, "CONC1D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, "CONC1-101");

        // Two independent "sessions" load the same version-0 row.
        Course staleCopy = newTransaction().execute(status -> courseRepository.findById(courseId).orElseThrow());

        // The first session commits its change, advancing the version.
        newTransaction().executeWithoutResult(status -> {
            Course fresh = courseRepository.findById(courseId).orElseThrow();
            fresh.update("Winner Title", new BigDecimal("4.0"), null, null, null, tenant.adminUserId(), java.time.Instant.now());
            courseRepository.saveAndFlush(fresh);
        });

        // The second session, still holding the stale (version-0) copy,
        // attempts to save its own change — this must fail loudly rather
        // than silently clobbering the first session's committed update.
        staleCopy.update("Loser Title", new BigDecimal("5.0"), null, null, null, tenant.adminUserId(), java.time.Instant.now());
        assertThatThrownBy(() -> newTransaction().executeWithoutResult(status -> courseRepository.saveAndFlush(staleCopy)))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
