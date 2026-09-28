package com.setu.catalog.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 7 §16 MEETING PATTERNS test matrix. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class MeetingPatternTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    MeetingPatternService meetingPatternService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    private record Fixture(Day7TestFixtures.Tenant tenant, UUID sectionId, UUID roomId) {
    }

    private Fixture setUp(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = fixtures.provisionPeriod(tenant, prefix + "P");
        UUID buildingId = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID roomId = fixtures.provisionRoom(tenant, buildingId, "101", 40);
        UUID sectionId = fixtures.provisionSection(tenant, courseId, periodId, "S1", 30, null);
        return new Fixture(tenant, sectionId, roomId);
    }

    @Test
    void validMeetingPatternCreation() {
        var f = setUp("MTG1");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(9, 0), LocalTime.of(10, 0),
            f.roomId, null, null);

        assertThat(result).isInstanceOf(MeetingPatternService.CreateResult.Success.class);
    }

    @Test
    void startNotBeforeEndIsRejected() {
        var f = setUp("MTG2");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(10, 0), LocalTime.of(10, 0),
            f.roomId, null, null);

        assertThat(result).isInstanceOf(MeetingPatternService.CreateResult.InvalidPatternData.class);
    }

    @Test
    void invalidEffectiveDatesAreRejected() {
        var f = setUp("MTG3");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(9, 0), LocalTime.of(10, 0),
            f.roomId, java.time.LocalDate.of(2025, 6, 1), java.time.LocalDate.of(2025, 1, 1));

        assertThat(result).isInstanceOf(MeetingPatternService.CreateResult.InvalidPatternData.class);
    }

    @Test
    void overlappingRoomMeetingIsRejected() {
        var f = setUp("MTG4");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(9, 0), LocalTime.of(10, 0), f.roomId, null, null);

        // Same room, same day, overlapping time — the DB EXCLUDE constraint
        // keys on room_id + day_of_week + time_range only (not section_id),
        // so this conflicts even for the same section.
        var result = meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(9, 30), LocalTime.of(10, 30),
            f.roomId, null, null);

        assertThat(result).isInstanceOf(MeetingPatternService.CreateResult.RoomOverlap.class);
    }

    @Test
    void nonOverlappingRoomMeetingsAreAccepted() {
        var f = setUp("MTG5");
        TestAuthentication.authenticateAs(f.tenant.adminUserId());
        meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(9, 0), LocalTime.of(10, 0), f.roomId, null, null);

        var result = meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(10, 0), LocalTime.of(11, 0),
            f.roomId, null, null);

        assertThat(result).isInstanceOf(MeetingPatternService.CreateResult.Success.class);
    }

    @Test
    void crossTenantRoomIsRejected() {
        var f = setUp("MTG6A");
        var otherTenant = fixtures.provisionTenant("MTG6B");
        UUID otherBuilding = fixtures.provisionBuilding(otherTenant, "MTG6BB");
        UUID otherRoom = fixtures.provisionRoom(otherTenant, otherBuilding, "999", 10);
        TestAuthentication.authenticateAs(f.tenant.adminUserId());

        var result = meetingPatternService.create(f.sectionId, (short) 2, LocalTime.of(9, 0), LocalTime.of(10, 0),
            otherRoom, null, null);

        assertThat(result).isInstanceOf(MeetingPatternService.CreateResult.TenantMismatch.class);
    }

    @Test
    void crossTenantSectionIsDenied() {
        var f = setUp("MTG7A");
        var otherAdmin = fixtures.provisionTenant("MTG7B");
        TestAuthentication.authenticateAs(otherAdmin.adminUserId());

        assertThatThrownBy(() -> meetingPatternService.create(f.sectionId, (short) 1, LocalTime.of(9, 0), LocalTime.of(10, 0),
            f.roomId, null, null)).isInstanceOf(AccessDeniedException.class);
    }
}
