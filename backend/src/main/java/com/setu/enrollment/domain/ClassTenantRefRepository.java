package com.setu.enrollment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Package-private alongside {@link ClassTenantRef} — see that class's javadoc.
 * Lives in {@code domain} rather than {@code infrastructure} solely because
 * {@code ClassTenantRef} is package-private and Spring Data repository
 * interfaces must be visible to the entity they front.
 */
interface ClassTenantRefRepository extends JpaRepository<ClassTenantRef, UUID> {
}
