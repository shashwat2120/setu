package com.setu.testsupport;

import com.setu.identity.domain.RoleCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Day 12 test fixtures — builds on Day 7/8 fixtures for tenant/student; adds a PLACEMENT_OFFICER provisioning helper, the first real user of that role in this codebase's test suite. */
@Component
public class Day12TestFixtures {

    private final Day7TestFixtures day7TestFixtures;

    public Day12TestFixtures(Day7TestFixtures day7TestFixtures) {
        this.day7TestFixtures = day7TestFixtures;
    }

    public UUID provisionPlacementOfficer(Day7TestFixtures.Tenant tenant, String prefix) {
        return day7TestFixtures.provisionUserWithRole(tenant.tenantId(), RoleCode.PLACEMENT_OFFICER, prefix);
    }
}
