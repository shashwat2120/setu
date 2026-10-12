import type { StaffAttendanceResponse } from '../../api/types';
import { monthName, summarizeMonth } from '../../lib/staffCalendar';

interface Props {
  records: StaffAttendanceResponse[] | null;
  todayIso: string | null;
}

/** "October so far": attendance % and present / late / absent / on-leave tiles, all computed from the member's real records. */
export function MonthSummaryCard({ records, todayIso }: Props) {
  const s = records && todayIso ? summarizeMonth(records, todayIso) : null;
  return (
    <section className="staff-card" aria-label="Month summary">
      <div className="staff-card-head">
        <h2>{todayIso ? `${monthName(todayIso)} so far` : 'This month so far'}</h2>
        {s && <span className="staff-muted">{s.workingDays} working {s.workingDays === 1 ? 'day' : 'days'}</span>}
      </div>
      {!s ? (
        <p className="staff-muted">Loading…</p>
      ) : (
        <>
          <p style={{ marginBottom: 10 }}>
            {s.attendancePct === null ? (
              <span className="staff-muted">Nothing to measure yet this month.</span>
            ) : (
              <>
                <span style={{ fontFamily: 'var(--font-display)', fontSize: 30, fontWeight: 600 }}>{s.attendancePct}%</span>
                <span className="staff-muted"> attendance</span>
              </>
            )}
          </p>
          <div className="month-tiles">
            <div className="month-tile tile-ok"><div className="l">Present</div><div className="n">{s.present}</div></div>
            <div className="month-tile tile-warn"><div className="l">Late</div><div className="n">{s.late}</div></div>
            <div className="month-tile tile-danger"><div className="l">Absent</div><div className="n">{s.absent}</div></div>
            <div className="month-tile tile-neutral"><div className="l">On leave</div><div className="n">{s.onLeave}</div></div>
          </div>
          <p className="staff-muted" style={{ marginTop: 8 }}>
            Half days count as half; leave days are not counted against you.
            {s.notRecorded > 0 ? ` ${s.notRecorded} working ${s.notRecorded === 1 ? 'day has' : 'days have'} no record.` : ''}
          </p>
        </>
      )}
    </section>
  );
}
