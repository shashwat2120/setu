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
    private final com.setu.shared.governance.OutboxEventWriter outboxEventWriter;

    public CollegeDirectoryService(CollegeRepository collegeRepository, com.setu.shared.governance.OutboxEventWriter outboxEventWriter) {
        this.collegeRepository = collegeRepository;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public UUID provisionCollege(String code, String name, String timezone) {
        College college = new College(UUID.randomUUID(), code, name, timezone, Instant.now());
        collegeRepository.save(college);
        outboxEventWriter.publish(new com.setu.institution.domain.CollegeCreatedEvent(UUID.randomUUID(), Instant.now(), college.id(), college.id(),
            UUID.randomUUID(), null, college.name(), college.timezone()));
        return college.id();
    }

    public boolean exists(UUID tenantId) {
        return collegeRepository.existsById(tenantId);
    }
}
