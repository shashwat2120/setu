import type { StaffAttendanceResponse } from '../api/types';

/**
 * Calendar helpers for the staff attendance page. Every date here is an ISO "YYYY-MM-DD" string in the COLLEGE's timezone
 * (the server supplies "today"); arithmetic goes through UTC dates so the browser's own timezone can never shift a day.
 */

const MS_PER_DAY = 86_400_000;

function toUtc(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function toIso(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function addDays(iso: string, days: number): string {
  return toIso(new Date(toUtc(iso).getTime() + days * MS_PER_DAY));
}

/** 0 = Monday ... 6 = Sunday. */
export function weekdayIndex(iso: string): number {
  return (toUtc(iso).getUTCDay() + 6) % 7;
}

export function isWeekend(iso: string): boolean {
  return weekdayIndex(iso) >= 5;
}

export function mondayOf(iso: string): string {
  return addDays(iso, -weekdayIndex(iso));
}

export function monthStart(iso: string): string {
  return `${iso.slice(0, 7)}-01`;
}

export function monthName(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', { month: 'long', timeZone: 'UTC' }).format(toUtc(iso));
}

/** "Thu, 1 Oct". */
export function shortDate(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(toUtc(iso));
}

/** "Mon" / "5". */
export function weekdayLabel(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', { weekday: 'short', timeZone: 'UTC' }).format(toUtc(iso));
}

export function dayOfMonth(iso: string): number {
  return toUtc(iso).getUTCDate();
}

/** Monday-to-Friday days from `fromIso` to `toIso`, inclusive. */
export function workingDaysBetween(fromIso: string, toIso_: string): number {
  let count = 0;
  for (let d = fromIso; d <= toIso_; d = addDays(d, 1)) {
    if (!isWeekend(d)) count += 1;
  }
  return count;
}

export interface MonthSummary {
  /** Working days (Mon-Fri) from the 1st through today. */
  workingDays: number;
  present: number;
  late: number;
  absent: number;
  onLeave: number;
  /** Past working days with no record at all (today is not counted until it has one). */
  notRecorded: number;
  /** Rounded %, or null when there is nothing to measure yet. Half days count as half; leave days are not counted against the person. */
  attendancePct: number | null;
}

/**
 * The month so far, computed only from real records. "Late" is a subset of days attended that is shown separately from on-time
 * "present" days, the way the tiles read; an on-time or late PRESENT counts 1 toward attendance and a HALF_DAY counts 0.5.
 */
export function summarizeMonth(records: StaffAttendanceResponse[], todayIso: string): MonthSummary {
  const start = monthStart(todayIso);
  const inMonth = records.filter((r) => r.attendanceDate >= start && r.attendanceDate <= todayIso);
  let present = 0;
  let late = 0;
  let absent = 0;
  let onLeave = 0;
  let attended = 0;
  for (const r of inMonth) {
    if (r.status === 'ON_LEAVE') {
      onLeave += 1;
    } else if (r.status === 'ABSENT') {
      absent += 1;
    } else {
      if (r.lateMinutes) late += 1;
      else present += 1;
      attended += r.status === 'HALF_DAY' ? 0.5 : 1;
    }
  }
  const workingDays = workingDaysBetween(start, todayIso);
  const todayRecorded = inMonth.some((r) => r.attendanceDate === todayIso);
  const weekdayRecords = inMonth.filter((r) => !isWeekend(r.attendanceDate)).length;
  const notRecorded = Math.max(0, workingDays - weekdayRecords - (todayRecorded || isWeekend(todayIso) ? 0 : 1));
  const denominator = workingDays - onLeave - (todayRecorded || isWeekend(todayIso) ? 0 : 1);
  const attendancePct = denominator > 0 ? Math.round((attended / denominator) * 100) : null;
  return { workingDays, present, late, absent, onLeave, notRecorded, attendancePct };
}

export type DayKind = 'present' | 'late' | 'halfDay' | 'absent' | 'onLeave' | 'noCheckOut' | 'upcoming' | 'notRecorded' | 'notCheckedIn';

export interface WeekDay {
  iso: string;
  isToday: boolean;
  kind: DayKind;
  record: StaffAttendanceResponse | null;
}

/** Monday-to-Friday of the week containing `todayIso`, each classified from its record (or lack of one). */
export function buildWeek(records: StaffAttendanceResponse[], todayIso: string): WeekDay[] {
  const byDate = new Map(records.map((r) => [r.attendanceDate, r]));
  const monday = mondayOf(todayIso);
  const days: WeekDay[] = [];
  for (let i = 0; i < 5; i += 1) {
    const iso = addDays(monday, i);
    const record = byDate.get(iso) ?? null;
    const isToday = iso === todayIso;
    let kind: DayKind;
    if (iso > todayIso) kind = 'upcoming';
    else if (!record) kind = isToday ? 'notCheckedIn' : 'notRecorded';
    else if (record.status === 'ON_LEAVE') kind = 'onLeave';
    else if (record.status === 'ABSENT') kind = 'absent';
    else if (record.checkInAt && !record.checkOutAt && !isToday) kind = 'noCheckOut';
    else if (record.status === 'HALF_DAY') kind = 'halfDay';
    else if (record.lateMinutes) kind = 'late';
    else kind = 'present';
    days.push({ iso, isToday, kind, record });
  }
  return days;
}

/** Minutes since midnight in `timeZone` for an instant given in epoch milliseconds. */
export function minutesOfDayInZone(ms: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone }).formatToParts(ms);
  const h = Number(parts.find((p) => p.type === 'hour')?.value ?? 0);
  const m = Number(parts.find((p) => p.type === 'minute')?.value ?? 0);
  return h * 60 + m;
}

/** "HH:mm" -> minutes since midnight. */
export function hhmmToMinutes(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}
