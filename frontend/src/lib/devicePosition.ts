/**
 * Asks the device where it is, once. Used by attendance: the instructor's device reports the classroom position when a session opens, and a student's
 * device reports where it is when checking in. Always resolves, never throws: a refusal, a device without location, an insecure page or a timeout all
 * give `null`, because location is a signal for the instructor and must never stand between a student and checking in.
 */
export interface DevicePosition {
  latitude: number;
  longitude: number;
  /** How accurate the device says the fix is, in metres. */
  accuracyMeters: number;
}

/** Why no position came back: the browser has blocked location for this site, the device could not work it out, it took too long, or the browser has no location. */
export type PositionProblem = 'denied' | 'unavailable' | 'timeout' | 'unsupported';

export type PositionResult = { ok: true; position: DevicePosition } | { ok: false; problem: PositionProblem };

/** What a person can do about each problem, in words for the page to show after "not shared". */
export function positionHint(problem: PositionProblem): string {
  switch (problem) {
    case 'denied':
      return 'Your browser has blocked location for this site. Click the pin icon in the address bar, choose Allow, then try again.';
    case 'unavailable':
      return 'Your device could not work out its position. Check that location services are turned on in your device settings.';
    case 'timeout':
      return 'Finding your position took too long. Try again where the signal is better.';
    default:
      return 'This browser cannot share a location.';
  }
}

/** Like {@link getDevicePosition} but says why there is no position. Always resolves, never throws. */
export function requestDevicePosition(timeoutMs = 8000): Promise<PositionResult> {
  return new Promise((resolve) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      resolve({ ok: false, problem: 'unsupported' });
      return;
    }
    let settled = false;
    const finish = (value: PositionResult) => {
      if (!settled) {
        settled = true;
        resolve(value);
      }
    };
    // a fallback in case the browser never calls back (a prompt left unanswered): the option timeout only starts once permission is given
    const guard = window.setTimeout(() => finish({ ok: false, problem: 'timeout' }), timeoutMs + 4000);
    try {
      navigator.geolocation.getCurrentPosition(
        (p) => {
          window.clearTimeout(guard);
          finish({ ok: true, position: { latitude: p.coords.latitude, longitude: p.coords.longitude, accuracyMeters: p.coords.accuracy } });
        },
        (error) => {
          window.clearTimeout(guard);
          // GeolocationPositionError: 1 permission denied, 2 position unavailable, 3 timeout
          finish({ ok: false, problem: error?.code === 1 ? 'denied' : error?.code === 3 ? 'timeout' : 'unavailable' });
        },
        { enableHighAccuracy: true, timeout: timeoutMs, maximumAge: 30000 },
      );
    } catch {
      window.clearTimeout(guard);
      finish({ ok: false, problem: 'unavailable' });
    }
  });
}

export function getDevicePosition(timeoutMs = 8000): Promise<DevicePosition | null> {
  return requestDevicePosition(timeoutMs).then((r) => (r.ok ? r.position : null));
}

export type CheckInLocationStatus = 'NEAR' | 'FAR' | 'NO_LOCATION' | 'NO_REFERENCE';

/**
 * What a check-in's location outcome means, in the words shown to the instructor (short) or the student (a sentence). A student's check-in is compared with
 * the classroom ({@code place} 'room', the default); a staff member's with the campus ('campus').
 */
export function locationLabel(status: string | null | undefined, distanceMeters?: number | null, place: 'room' | 'campus' = 'room'): string {
  const near = place === 'campus' ? 'On campus' : 'In the room';
  const far = place === 'campus' ? 'Away from campus' : 'Away from the room';
  switch (status) {
    case 'NEAR': return distanceMeters != null ? `${near} (${distanceMeters} m)` : near;
    case 'FAR': return distanceMeters != null ? `${far} (${formatDistance(distanceMeters)})` : far;
    case 'NO_LOCATION': return 'Location not shared';
    case 'NO_REFERENCE': return place === 'campus' ? 'No campus position' : 'No classroom position';
    default: return '—';
  }
}

export function formatDistance(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${meters} m`;
}
