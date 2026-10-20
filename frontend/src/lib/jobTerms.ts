import type { JobResponse } from '../api/types';

/** Plain labels for the job types the server knows. */
export const JOB_TYPE_LABELS: Record<string, string> = { FULL_TIME: 'Full time', INTERNSHIP: 'Internship', CONTRACT: 'Contract' };

/** A number as a person writes it: 7, 7.5, 7.25, never 7.50. */
export function plainNumber(value: number | string): string {
  const n = Number(value);
  return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : String(value);
}

/** The graduating batches an officer can pick for a job: from last year to five years ahead, plus the job's own batch if it is outside that range. */
export function batchYears(current?: number | null, now: Date = new Date()): number[] {
  const year = now.getFullYear();
  const years = Array.from({ length: 7 }, (_, i) => year - 1 + i);
  if (current != null && !years.includes(current)) years.push(current);
  return years.sort((a, b) => a - b);
}

/** An instant for an `<input type="datetime-local">`, in the viewer's own time zone ('' when there is none). */
export function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** The instant a `datetime-local` value means in the viewer's own time zone, as the ISO string the server takes (null when empty). */
export function fromLocalInput(value: string): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

/** What the job says about itself (the facts, not the rules), one short phrase each, for a card: "₹12.5 LPA", "Pune", "3 openings", "Internship". */
export function jobFacts(job: Pick<JobResponse, 'packageLpa' | 'location' | 'openings' | 'jobType'>): string[] {
  const facts: string[] = [];
  if (job.jobType) facts.push(JOB_TYPE_LABELS[job.jobType] ?? job.jobType);
  if (job.packageLpa != null) facts.push(`₹${plainNumber(job.packageLpa)} LPA`);
  if (job.location) facts.push(job.location);
  if (job.openings != null) facts.push(`${job.openings} opening${job.openings === 1 ? '' : 's'}`);
  return facts;
}

/** The rules a student must meet, one sentence each, so a student can read what a job asks before anything is checked. */
export function jobRequirements(job: Pick<JobResponse, 'minCgpa' | 'minAttendancePct' | 'applicationDeadline' | 'allowedProgramIds' | 'graduatingYear'>, now: Date = new Date()): string[] {
  const rules: string[] = [];
  if (job.minCgpa != null) rules.push(`CGPA of at least ${plainNumber(job.minCgpa)}`);
  if (job.minAttendancePct != null) rules.push(`Attendance of at least ${plainNumber(job.minAttendancePct)}%`);
  if (job.allowedProgramIds && job.allowedProgramIds.length > 0) rules.push('Open to certain programs only');
  if (job.graduatingYear != null) rules.push(`Batch of ${job.graduatingYear} only`);
  if (job.applicationDeadline) {
    const when = new Date(job.applicationDeadline);
    const text = when.toLocaleString([], { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
    rules.push(when.getTime() <= now.getTime() ? `Deadline passed (${text})` : `Apply by ${text}`);
  }
  return rules;
}
