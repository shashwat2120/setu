package com.setu.institution.application;

import com.setu.institution.domain.College;
import com.setu.institution.infrastructure.CollegeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Institution module's public port — Day 4 only needs tenant provisioning/existence-checking for authorization purposes; full institution CRUD is out of scope here. */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class CollegeDirectoryService {

    private final CollegeRepository collegeRepository;

    public CollegeDirectoryService(CollegeRepository collegeRepository) {
        this.collegeRepository = collegeRepository;
    }

    @Transactional
    public UUID provisionCollege(String code, String name, String timezone) {
        College college = new College(UUID.randomUUID(), code, name, timezone, Instant.now());
        collegeRepository.save(college);
        return college.id();
    }

    public boolean exists(UUID tenantId) {
        return collegeRepository.existsById(tenantId);
    }
}
