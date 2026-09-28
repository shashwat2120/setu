package com.setu.enrollment.domain;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Public in-module wrapper around the package-private
 * {@link ClassTenantRefRepository}/{@link ClassTenantRef} pair — see
 * {@code ClassTenantRef}'s javadoc for why those stay package-private.
 * {@code enrollment.application.CourseSectionService} lives in a different
 * package and cannot reference a package-private type directly, so this
 * class exists purely to return a bare {@code UUID}, never the entity,
 * across that package boundary while keeping {@code ClassTenantRef} itself
 * un-exported. This stays entirely within the {@code enrollment} module.
 */
@Component
public class ClassTenantLookup {

    private final ClassTenantRefRepository repository;

    public ClassTenantLookup(ClassTenantRefRepository repository) {
        this.repository = repository;
    }

    public Optional<UUID> findTenantId(UUID classId) {
        return repository.findById(classId).map(ClassTenantRef::tenantId);
    }
}
