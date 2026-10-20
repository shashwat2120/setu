package com.setu.attendance.infrastructure;

import com.setu.attendance.domain.StaffLocationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StaffLocationPolicyRepository extends JpaRepository<StaffLocationPolicy, UUID> {
}
