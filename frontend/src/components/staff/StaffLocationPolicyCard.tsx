import { useEffect, useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { StaffLocationPolicy } from '../../api/types';
import { positionHint, requestDevicePosition } from '../../lib/devicePosition';

interface Draft {
  enabled: boolean;
  latitude: string;
  longitude: string;
  radius: string;
}

const toDraft = (p: StaffLocationPolicy): Draft => ({
  enabled: p.enabled,
  latitude: p.campusLatitude == null ? '' : String(p.campusLatitude),
  longitude: p.campusLongitude == null ? '' : String(p.campusLongitude),
  radius: String(p.radiusMeters),
});

/**
 * The college's choice about recording where staff check in from. Off until switched on here, and switching it on needs the campus position. It is
 * information for this page and never a rule: a check-in is never refused or changed because of where the device said it was, and only the outcome
 * (near, far, not shared) is kept, never anyone's coordinates.
 */
export function StaffLocationPolicyCard() {
  const policy = useApiQuery<StaffLocationPolicy>('/staff-attendance/location-policy');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  const [locating, setLocating] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  useEffect(() => {
    if (policy.data) setDraft(toDraft(policy.data));
  }, [policy.data]);

  const useThisDevice = async () => {
    setLocating(true);
    setMessage(null);
    const found = await requestDevicePosition();
    setLocating(false);
    if (!found.ok) {
      setMessage({ ok: false, text: `This device did not share its position. ${positionHint(found.problem)} Or type the campus latitude and longitude instead.` });
      return;
    }
    const position = found.position;
    setDraft((d) => (d ? { ...d, latitude: position.latitude.toFixed(6), longitude: position.longitude.toFixed(6) } : d));
  };

  const save = async () => {
    if (!draft) return;
    setBusy(true);
    setMessage(null);
    try {
      const hasPosition = draft.latitude.trim() !== '' && draft.longitude.trim() !== '';
      await apiFetch<StaffLocationPolicy>('/staff-attendance/location-policy', {
        method: 'PUT',
        body: {
          enabled: draft.enabled,
          campusLatitude: hasPosition ? Number(draft.latitude) : null,
          campusLongitude: hasPosition ? Number(draft.longitude) : null,
          radiusMeters: Number(draft.radius),
        },
      });
      policy.refetch();
      setMessage({ ok: true, text: draft.enabled ? 'Saved. Staff check-ins now record where they were made from.' : 'Saved. Staff check-in location is off.' });
    } catch (e) {
      setMessage({ ok: false, text: e instanceof ApiError ? e.message : 'Could not save this setting.' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card" style={{ marginBottom: 24 }} aria-label="Staff check-in location">
      <h2 style={{ fontSize: 16, marginBottom: 4 }}>Where staff check in from</h2>
      <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>
        Off by default. When on, each staff check-in records whether the device was on campus, away from it, or did not share a position, and it shows in the
        table below. It is information for you and never a rule: a check-in is never refused because of it, and only how close the device was is kept,
        never anyone's coordinates. Staff are told on the check-in page before their device is asked.
      </p>
      {policy.error ? (
        <p role="alert" className="form-error">Could not load this setting. {policy.error.message}</p>
      ) : !draft ? (
        <p style={{ color: 'var(--ink-soft)' }}>Loading…</p>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); save(); }} style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'flex-end' }}>
          <div className="form-field" style={{ flexBasis: '100%' }}>
            <label htmlFor="loc-enabled" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <input id="loc-enabled" type="checkbox" checked={draft.enabled} onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })} />
              Record where staff check in from
            </label>
          </div>
          <div className="form-field">
            <label htmlFor="loc-lat">Campus latitude</label>
            <input id="loc-lat" inputMode="decimal" value={draft.latitude} onChange={(e) => setDraft({ ...draft, latitude: e.target.value })} style={{ width: 150 }} />
          </div>
          <div className="form-field">
            <label htmlFor="loc-lon">Campus longitude</label>
            <input id="loc-lon" inputMode="decimal" value={draft.longitude} onChange={(e) => setDraft({ ...draft, longitude: e.target.value })} style={{ width: 150 }} />
          </div>
          <div className="form-field">
            <label htmlFor="loc-radius">Counts as on campus within (metres)</label>
            <input id="loc-radius" type="number" min={50} max={5000} value={draft.radius} onChange={(e) => setDraft({ ...draft, radius: e.target.value })} required style={{ width: 150 }} />
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn btn-ghost" onClick={useThisDevice} disabled={locating || busy}>
              {locating ? 'Locating…' : "Use this device's position"}
            </button>
            <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
          </div>
        </form>
      )}
      {message && (
        <p role={message.ok ? 'status' : 'alert'} className={message.ok ? undefined : 'form-error'} style={{ marginTop: 10, fontSize: 13 }}>{message.text}</p>
      )}
    </section>
  );
}
