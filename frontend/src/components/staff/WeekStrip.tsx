import type { StaffAttendanceResponse } from '../../api/types';
import { addDays, buildWeek, dayOfMonth, mondayOf, weekdayLabel, type DayKind } from '../../lib/staffCalendar';

interface Props {
  records: StaffAttendanceResponse[] | null;
  todayIso: string | null;
  onFullHistory: () => void;
  /** A past day with a check-in but no check-out: the tile offers to start a correction for it. */
  onFixDay: (iso: string) => void;
}

const time = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : null);

const LABEL: Record<DayKind, string> = {
  present: 'Present',
  late: 'Late',
  halfDay: 'Half day',
  absent: 'Absent',
  onLeave: 'On leave',
  noCheckOut: 'No check-out',
  upcoming: 'Upcoming',
  notRecorded: 'Not recorded',
  notCheckedIn: 'Not checked in',
};

const COLOUR: Partial<Record<DayKind, string>> = {
  present: 'var(--verified)',
  late: 'var(--accent)',
  halfDay: 'var(--accent)',
  absent: 'var(--flag)',
  noCheckOut: 'var(--flag)',
};

/** Monday to Friday of this week (plus the weekend), each classified from the real records. */
export function WeekStrip({ records, todayIso, onFullHistory, onFixDay }: Props) {
  const days = records && todayIso ? buildWeek(records, todayIso) : null;
  const saturday = todayIso ? addDays(mondayOf(todayIso), 5) : null;

  return (
    <section className="staff-card staff-span" aria-label="This week">
      <div className="staff-card-head">
        <h2>This week</h2>
        <button type="button" className="staff-link" onClick={onFullHistory}>Full history</button>
      </div>
      {!days ? (
        <p className="staff-muted">Loading…</p>
      ) : (
        <div className="week-strip">
          {days.map((d) => {
            const inT = time(d.record?.checkInAt ?? null);
            const outT = time(d.record?.checkOutAt ?? null);
            const times = inT ? `${inT} – ${outT ?? (d.isToday ? 'now' : '…')}` : null;
            const content = (
              <>
                <div className="d">{weekdayLabel(d.iso)} {dayOfMonth(d.iso)}{d.isToday ? ' · Today' : ''}</div>
                <div className="s" style={{ color: COLOUR[d.kind] }}>{LABEL[d.kind]}</div>
                <div className="t">{times ?? ' '}</div>
              </>
            );
            const cls = `week-day${d.isToday ? ' today' : ''}${d.kind === 'upcoming' ? ' muted' : ''}`;
            return d.kind === 'noCheckOut' ? (
              <button key={d.iso} type="button" className={cls} onClick={() => onFixDay(d.iso)} title="Request a correction for this day">{content}</button>
            ) : (
              <div key={d.iso} className={cls}>{content}</div>
            );
          })}
          {saturday && (
            <div className="week-day weekend muted">
              <div className="d">{weekdayLabel(saturday)} {dayOfMonth(saturday)}</div>
              <div className="s">Weekend</div>
              <div className="t">&nbsp;</div>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
