import { useState } from 'react';
import type { StaffAttendancePolicy, StaffAttendanceToday } from '../../api/types';
import { hhmmToMinutes, minutesOfDayInZone } from '../../lib/staffCalendar';
import { clockParts, formatHm, formatHms, formatLongDate, formatShiftTime } from '../../lib/staffTime';

interface Props {
  today: StaffAttendanceToday | null;
  policy: StaffAttendancePolicy | null;
  /** Server-synced "now" in epoch ms. */
  now: number;
  busy: boolean;
  message: string | null;
  onAct: (path: string) => void;
}

const time = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '—');

/** The live clock, the single check-in/check-out button (check-out asks for confirmation), the shift progress bar and today's three figures. */
export function ClockCard({ today, policy, now, busy, message, onAct }: Props) {
  const [confirming, setConfirming] = useState(false);
  const zone = today?.timeZone ?? 'UTC';
  const record = today?.record ?? null;
  const canCheckIn = !!today && record === null;
  const canCheckOut = !!record && record.checkInAt !== null && record.checkOutAt === null;
  const inMs = record?.checkInAt ? Date.parse(record.checkInAt) : null;
  const outMs = record?.checkOutAt ? Date.parse(record.checkOutAt) : null;
  const onClockMinutes = inMs === null ? null : outMs !== null ? (record?.workedMinutes ?? (outMs - inMs) / 60000) : (now - inMs) / 60000;
  const { hm, period } = clockParts(now, zone);

  // shift progress: where "now" sits between the shift start and end, and where the check-in landed
  let progress: { pct: number; tickPct: number | null } | null = null;
  if (policy) {
    const start = hhmmToMinutes(policy.shiftStart);
    const end = hhmmToMinutes(policy.shiftEnd);
    const span = end - start;
    const clamp = (v: number) => Math.min(100, Math.max(0, v));
    progress = {
      pct: clamp(((minutesOfDayInZone(now, zone) - start) / span) * 100),
      tickPct: inMs === null ? null : clamp(((minutesOfDayInZone(inMs, zone) - start) / span) * 100),
    };
  }

  const status = (() => {
    if (!today) return { cls: 'staff-chip-neutral', label: 'Loading…' };
    if (!record) return { cls: 'staff-chip-neutral', label: 'Not checked in' };
    if (!record.checkInAt) return { cls: 'staff-chip-neutral', label: 'Recorded by an administrator' };
    if (outMs !== null) return { cls: 'staff-chip-ok staff-chip-dot', label: 'Checked out' };
    return { cls: 'staff-chip-ok staff-chip-dot', label: 'Checked in' };
  })();

  const buttonLabel = canCheckOut ? 'Check out' : record?.checkInAt ? 'Done for today' : record ? 'Recorded for today' : 'Check in';
  const caption = canCheckOut
    ? "You'll be asked to confirm"
    : canCheckIn
      ? (policy ? `Your shift starts at ${formatShiftTime(policy.shiftStart)}` : 'Starts your day')
      : record?.checkInAt && outMs !== null
        ? `Worked ${formatHm(onClockMinutes ?? 0)} today`
        : '';

  return (
    <section className="staff-card" aria-label="Attendance clock">
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
            <span className={`staff-chip ${status.cls}`}>{status.label}</span>
            {record?.lateMinutes && policy ? (
              <span className="staff-chip staff-chip-warn">Late · {formatHm(record.lateMinutes)} after {formatShiftTime(policy.shiftStart)}</span>
            ) : null}
            {record?.shortHours ? <span className="staff-chip staff-chip-warn">Short hours</span> : null}
            {record && record.status === 'HALF_DAY' ? <span className="staff-chip staff-chip-neutral">Half day</span> : null}
          </div>
          <div style={{ fontFamily: 'var(--font-display)', lineHeight: 1, display: 'flex', alignItems: 'baseline', gap: 6 }} aria-label="Current time">
            <span style={{ fontSize: 44, fontWeight: 600 }}>{today ? hm : '—'}</span>
            <span style={{ fontSize: 18, color: 'var(--ink-soft)' }}>{today ? period : ''}</span>
          </div>
          <p className="staff-muted" style={{ marginTop: 6 }}>
            {today ? `${formatLongDate(now, zone)} · College time (${zone})` : 'Loading…'}
          </p>
        </div>

        <div style={{ width: 168, maxWidth: '100%', textAlign: 'center' }}>
          {confirming && canCheckOut ? (
            <>
              <button
                type="button"
                className="btn btn-primary"
                style={{ width: '100%', justifyContent: 'center', padding: '9px 16px', fontSize: 14, background: 'var(--flag)', borderColor: 'var(--flag)' }}
                onClick={() => { setConfirming(false); onAct('/staff-attendance/me/check-out'); }}
                disabled={busy}
              >
                Confirm check out
              </button>
              <button type="button" className="staff-link" style={{ marginTop: 8 }} onClick={() => setConfirming(false)}>Cancel</button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="btn btn-primary"
                style={{ width: '100%', justifyContent: 'center', padding: '10px 16px', fontSize: 14, ...(canCheckOut ? { background: 'var(--flag)', borderColor: 'var(--flag)' } : {}) }}
                onClick={() => (canCheckOut ? setConfirming(true) : onAct('/staff-attendance/me/check-in'))}
                disabled={busy || !(canCheckIn || canCheckOut)}
              >
                {buttonLabel}
              </button>
              <p className="staff-muted" style={{ marginTop: 6 }}>{caption}</p>
            </>
          )}
        </div>
      </div>

      {policy && progress && (
        <div style={{ marginTop: 14 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }} className="staff-muted">
            <span>{formatShiftTime(policy.shiftStart)} shift start</span>
            <span>{formatShiftTime(policy.shiftEnd)} shift end</span>
          </div>
          <div className="shift-bar" role="img" aria-label="Progress through today's shift">
            <div className="shift-bar-fill" style={{ width: `${progress.pct}%` }} />
            {progress.tickPct !== null && <div className="shift-tick" style={{ left: `${progress.tickPct}%` }} title={`Checked in ${time(record?.checkInAt ?? null)}`} />}
          </div>
        </div>
      )}

      <div className="staff-stats" style={{ marginTop: 14 }}>
        <div>
          <div className="staff-stat-label">Checked in</div>
          <div className="staff-stat-value mono">{time(record?.checkInAt ?? null)}</div>
        </div>
        <div>
          <div className="staff-stat-label">{outMs === null ? 'On the clock' : 'Worked'}</div>
          <div className="staff-stat-value mono" aria-live="off">
            {onClockMinutes === null ? '—' : outMs === null && inMs !== null ? formatHms((now - inMs) / 1000) : formatHm(onClockMinutes)}
          </div>
        </div>
        <div>
          <div className="staff-stat-label">Expected hours</div>
          <div className="staff-stat-value mono">{policy ? formatHm(policy.fullDayMinutes) : '—'}</div>
        </div>
      </div>

      {record && record.status === 'HALF_DAY' && record.source === 'SELF' && policy && outMs !== null && (
        <p className="staff-muted" style={{ marginTop: 10 }}>
          Recorded as a half day: a full day needs {formatHm(policy.fullDayMinutes)}.
        </p>
      )}
      {record && !record.checkInAt && (
        <p className="staff-muted" style={{ marginTop: 10 }}>
          Today was recorded by an administrator{record.correctionReason ? `: ${record.correctionReason}` : ''}.
        </p>
      )}
      {message && <p role="alert" className="form-error" style={{ marginTop: 10 }}>{message}</p>}
    </section>
  );
}
