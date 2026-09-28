package com.setu.catalog.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day7TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Day 7 §16 BUILDINGS and ROOMS test matrices. */
// Not @Transactional (Sprint 5D hardening): several Day 7 fixture methods now insert through a REQUIRES_NEW guard
// (see e.g. BuildingInsertGuard), so an uncommitted @Transactional test transaction's own fixture rows would be
// invisible to that guard's separate connection — the same lesson already applied across this codebase's other
// REQUIRES_NEW-touching test classes.
@SpringBootTest
class BuildingRoomTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    BuildingService buildingService;
    @Autowired
    RoomService roomService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void validBuildingCreation() {
        var tenant = fixtures.provisionTenant("BLD1");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = buildingService.create(tenant.tenantId(), "BLD1-B1", "Main Block");

        assertThat(result).isInstanceOf(BuildingService.CreateResult.Success.class);
    }

    @Test
    void duplicateTenantCodeIsRejected() {
        var tenant = fixtures.provisionTenant("BLD2");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        buildingService.create(tenant.tenantId(), "DUP", "First");

        var result = buildingService.create(tenant.tenantId(), "DUP", "Second");

        assertThat(result).isInstanceOf(BuildingService.CreateResult.CodeAlreadyInUse.class);
    }

    @Test
    void wrongTenantAccessIsDenied() {
        var tenantA = fixtures.provisionTenant("BLD3A");
        var tenantB = fixtures.provisionTenant("BLD3B");
        UUID buildingB = fixtures.provisionBuilding(tenantB, "BLD3-B1");
        TestAuthentication.authenticateAs(tenantA.adminUserId());

        assertThatThrownBy(() -> buildingService.update(buildingB, "Renamed"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validRoomCreation() {
        var tenant = fixtures.provisionTenant("RM1");
        UUID buildingId = fixtures.provisionBuilding(tenant, "RM1-B1");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = roomService.create(buildingId, "101", 40, null);

        assertThat(result).isInstanceOf(RoomService.CreateResult.Success.class);
    }

    @Test
    void invalidCapacityIsRejected() {
        var tenant = fixtures.provisionTenant("RM2");
        UUID buildingId = fixtures.provisionBuilding(tenant, "RM2-B1");
        TestAuthentication.authenticateAs(tenant.adminUserId());

        var result = roomService.create(buildingId, "101", 0, null);

        assertThat(result).isInstanceOf(RoomService.CreateResult.InvalidRoomData.class);
    }

    @Test
    void duplicateCodeWithinBuildingIsRejected() {
        var tenant = fixtures.provisionTenant("RM3");
        UUID buildingId = fixtures.provisionBuilding(tenant, "RM3-B1");
        TestAuthentication.authenticateAs(tenant.adminUserId());
        roomService.create(buildingId, "101", 40, null);

        var result = roomService.create(buildingId, "101", 30, null);

        assertThat(result).isInstanceOf(RoomService.CreateResult.CodeAlreadyInUse.class);
    }

    @Test
    void crossTenantBuildingIsDenied() {
        var tenantA = fixtures.provisionTenant("RM4A");
        var tenantB = fixtures.provisionTenant("RM4B");
        UUID buildingB = fixtures.provisionBuilding(tenantB, "RM4-B1");
        TestAuthentication.authenticateAs(tenantA.adminUserId());

        assertThatThrownBy(() -> roomService.create(buildingB, "999", 20, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void roomCapacityQueryReflectsCreatedRoom() {
        var tenant = fixtures.provisionTenant("RM5");
        UUID buildingId = fixtures.provisionBuilding(tenant, "RM5-B1");
        UUID roomId = fixtures.provisionRoom(tenant, buildingId, "205", 25);

        var info = roomService.findTenantAndCapacity(roomId).orElseThrow();

        assertThat(info.tenantId()).isEqualTo(tenant.tenantId());
        assertThat(info.capacity()).isEqualTo(25);
    }
}
