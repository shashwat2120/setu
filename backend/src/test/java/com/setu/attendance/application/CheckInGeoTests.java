package com.setu.attendance.application;

import com.setu.attendance.domain.CheckInLocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules that turn "where the student's device says it is" into a signal for the instructor: distance, the accuracy allowance, and every way a position can be missing. */
class CheckInGeoTests {

    // a classroom, and points a known distance north of it (0.001 degrees of latitude is about 111 m)
    private static final double LAT = 19.0760;
    private static final double LON = 72.8777;

    private static CheckInGeo.Outcome at(double metresNorth, Double accuracy) {
        return CheckInGeo.assess(LAT, LON, LAT + metresNorth / 111_195.0, LON, accuracy);
    }

    @Test
    void haversineDistanceIsRightOverShortAndLongRanges() {
        assertThat(CheckInGeo.distanceMeters(LAT, LON, LAT, LON)).isZero();
        assertThat(CheckInGeo.distanceMeters(LAT, LON, LAT + 0.001, LON)).isBetween(110.0, 112.5);
        // Mumbai to Delhi is about 1,150 km
        assertThat(CheckInGeo.distanceMeters(19.0760, 72.8777, 28.7041, 77.1025)).isBetween(1_140_000.0, 1_165_000.0);
    }

    @Test
    void aCheckInInTheRoomIsNear() {
        assertThat(at(0, 5.0).status()).isEqualTo(CheckInLocation.NEAR);
        var close = at(50, 10.0);
        assertThat(close.status()).isEqualTo(CheckInLocation.NEAR);
        assertThat(close.distanceMeters()).isBetween(48, 52);
        assertThat(close.accuracyMeters()).isEqualTo(10);
    }

    @Test
    void aCheckInFarAwayIsFarAndSaysHowFar() {
        var away = at(1_000, 20.0);
        assertThat(away.status()).isEqualTo(CheckInLocation.FAR);
        assertThat(away.distanceMeters()).isBetween(990, 1010);
    }

    @Test
    void theDevicesOwnAccuracyIsAllowedForSoAPoorFixIndoorsIsNotFlaggedFalsely() {
        // 130 m away: outside the 100 m radius for a precise fix, inside it when the device admits it could be 50 m out
        assertThat(at(130, 10.0).status()).isEqualTo(CheckInLocation.FAR);
        assertThat(at(130, 50.0).status()).isEqualTo(CheckInLocation.NEAR);
    }

    @Test
    void noPositionFromTheStudentIsNoLocationWhateverTheSessionHas() {
        assertThat(CheckInGeo.assess(LAT, LON, null, null, null).status()).isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(CheckInGeo.assess(LAT, LON, LAT, null, 5.0).status()).as("half a position is no position").isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(CheckInGeo.assess(null, null, null, null, null).status()).isEqualTo(CheckInLocation.NO_LOCATION);
    }

    @Test
    void aPositionThatIsNotAPositionIsNoLocationNotAnError() {
        assertThat(CheckInGeo.assess(LAT, LON, 95.0, 72.0, 5.0).status()).isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(CheckInGeo.assess(LAT, LON, 19.0, 200.0, 5.0).status()).isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(CheckInGeo.assess(LAT, LON, Double.NaN, 72.0, 5.0).status()).isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(CheckInGeo.assess(LAT, LON, Double.POSITIVE_INFINITY, 72.0, 5.0).status()).isEqualTo(CheckInLocation.NO_LOCATION);
    }

    @Test
    void aDeviceThatClaimsToBeAccurateOnlyToWithinAKilometreIsTreatedAsNotKnowingWhereItIs() {
        assertThat(at(10, 5_000.0).status()).isEqualTo(CheckInLocation.NO_LOCATION);
        assertThat(at(10, 5_000.0).distanceMeters()).isNull();
    }

    @Test
    void anUnknownOrNonsenseAccuracyIsTakenAsZeroNeverAsAnAllowance() {
        assertThat(at(130, null).status()).isEqualTo(CheckInLocation.FAR);
        assertThat(at(130, -50.0).status()).isEqualTo(CheckInLocation.FAR);
        assertThat(at(130, Double.NaN).status()).isEqualTo(CheckInLocation.FAR);
    }

    @Test
    void aSessionWithoutAClassroomPositionHasNothingToCompareWith() {
        var outcome = CheckInGeo.assess(null, null, LAT, LON, 12.0);
        assertThat(outcome.status()).isEqualTo(CheckInLocation.NO_REFERENCE);
        assertThat(outcome.distanceMeters()).isNull();
        assertThat(outcome.accuracyMeters()).isEqualTo(12);
        assertThat(CheckInGeo.assess(95.0, LON, LAT, LON, 12.0).status()).as("an invalid classroom position is no position").isEqualTo(CheckInLocation.NO_REFERENCE);
    }
}
