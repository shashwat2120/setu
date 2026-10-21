import type { ResumeContent, ResumeItem, ResumeRecord } from '../api/types';
import { plainNumber } from './jobTerms';

export const ITEM_KINDS = ['PROJECT', 'CERTIFICATION', 'EXPERIENCE'] as const;
export const KIND_LABELS: Record<string, string> = { PROJECT: 'Project', CERTIFICATION: 'Certification', EXPERIENCE: 'Experience' };
export const KIND_HEADINGS: Record<string, string> = { PROJECT: 'Projects', CERTIFICATION: 'Certifications', EXPERIENCE: 'Experience' };

/** Whether an address is one the page may show as a link: https only, so nothing executable is ever made clickable. */
export function isHttpsUrl(value: string | null | undefined): value is string {
  if (!value) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname !== '';
  } catch {
    return false;
  }
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function monthYear(iso: string): string {
  const [y, m] = iso.split('-');
  const month = Number(m);
  return month >= 1 && month <= 12 ? `${MONTHS[month - 1]} ${y}` : y;
}

/** "Jan 2026 – present", "Jan 2026 – Mar 2026", "Jan 2026", or '' when there are no dates. */
export function datesText(item: Pick<ResumeItem, 'startDate' | 'endDate'>): string {
  const start = item.startDate ? monthYear(item.startDate) : null;
  const end = item.endDate ? monthYear(item.endDate) : null;
  if (start && end) return `${start} – ${end}`;
  if (start) return `${start} – present`;
  return end ?? '';
}

/** The facts the college itself holds, as one line: "Batch of 2028 · CGPA 2.25 · Attendance 33.3%" (only what is known). */
export function recordLine(record: ResumeRecord): string {
  return [
    record.expectedGraduationYear != null ? `Batch of ${record.expectedGraduationYear}` : null,
    record.cgpa != null ? `CGPA ${plainNumber(record.cgpa)}` : null,
    record.attendancePercentage != null ? `Attendance ${plainNumber(record.attendancePercentage)}%` : null,
  ].filter(Boolean).join(' · ');
}

/** "245 KB", "1.4 MB": a file's size as a person reads it. */
export function sizeText(bytes: number): string {
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export const MAX_FILE_BYTES = 2 * 1024 * 1024;

/** Why a chosen file cannot be uploaded, from what the browser knows before sending it; null if it may be tried. The server judges the content. */
export function fileProblem(file: { name: string; size: number }): string | null {
  if (file.size === 0) return 'That file is empty.';
  if (file.size > MAX_FILE_BYTES) return `That file is ${sizeText(file.size)}; the limit is 2 MB.`;
  if (!/\.pdf$/i.test(file.name)) return 'Only PDF files are accepted.';
  return null;
}

export const emptyContent = (): ResumeContent => ({ headline: '', summary: '', linkedinUrl: '', githubUrl: '', portfolioUrl: '', items: [] });

/** The form's values as the server takes them: blanks are nothing, entries without a title are dropped, text is trimmed. */
export function toRequestFields(form: ResumeContent) {
  const blank = (v: string | null | undefined) => (v == null || v.trim() === '' ? null : v.trim());
  return {
    headline: blank(form.headline),
    summary: blank(form.summary),
    linkedinUrl: blank(form.linkedinUrl),
    githubUrl: blank(form.githubUrl),
    portfolioUrl: blank(form.portfolioUrl),
    items: form.items.filter((i) => i.title.trim() !== '').map((i) => ({
      kind: i.kind, title: i.title.trim(), organisation: blank(i.organisation), description: blank(i.description), startDate: blank(i.startDate), endDate: blank(i.endDate),
    })),
  };
}
