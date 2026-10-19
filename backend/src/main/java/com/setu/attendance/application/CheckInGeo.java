package com.setu.attendance.application;

import com.setu.attendance.domain.CheckInLocation;

/**
 * Compares the position a student's device reports at check-in with the classroom position of the session. A signal for the instructor, with the
 * limits of a phone's location built in: a device reports how accurate it believes it is, and a check-in is only {@code FAR} when it is outside
 * the radius even if the device is as wrong as it says. Indoors that accuracy can be tens of metres, which is why this flags and never blocks.
 */
final class CheckInGeo {

    /** How far from the classroom position a check-in may be and still count as in the room: a large lecture hall plus its surroundings. */
    static final double RADIUS_METERS = 100;
    /** A device that claims to be accurate to more than this is treated as not knowing where it is. */
    static final double MAX_ACCURACY_METERS = 1000;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private CheckInGeo() {
    }

    /** The outcome of one comparison; distance and accuracy are whole metres, absent when there was nothing to compare. */
    record Outcome(CheckInLocation status, Integer distanceMeters, Integer accuracyMeters) {
    }

    static boolean isValidPosition(Double latitude, Double longitude) {
        return latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
            && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    /** Great-circle distance in metres (haversine). */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * @param anchorLatitude  the session's classroom position, or null when the instructor shared none
     * @param latitude        what the student's device reported, or null when it reported nothing
     * @param accuracyMeters  how accurate the device says it is; null or negative is taken as unknown (0)
     */
    static Outcome assess(Double anchorLatitude, Double anchorLongitude, Double latitude, Double longitude, Double accuracyMeters) {
        if (!isValidPosition(latitude, longitude)) {
            return new Outcome(CheckInLocation.NO_LOCATION, null, null);
        }
        double accuracy = accuracyMeters == null || !Double.isFinite(accuracyMeters) || accuracyMeters < 0 ? 0 : accuracyMeters;
        if (accuracy > MAX_ACCURACY_METERS) {
            return new Outcome(CheckInLocation.NO_LOCATION, null, null);   // "somewhere within a kilometre" says nothing about a classroom
        }
        Integer reportedAccuracy = (int) Math.round(accuracy);
        if (!isValidPosition(anchorLatitude, anchorLongitude)) {
            return new Outcome(CheckInLocation.NO_REFERENCE, null, reportedAccuracy);
        }
        double distance = distanceMeters(anchorLatitude, anchorLongitude, latitude, longitude);
        CheckInLocation status = distance - accuracy <= RADIUS_METERS ? CheckInLocation.NEAR : CheckInLocation.FAR;
        return new Outcome(status, (int) Math.round(distance), reportedAccuracy);
    }
}
