package com.setu.institution.infrastructure;

import com.setu.institution.domain.College;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CollegeRepository extends JpaRepository<College, UUID> {
}
