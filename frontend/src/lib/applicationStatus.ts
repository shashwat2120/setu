import type { ApplicationResponse, PlacementRecordResponse } from '../api/types';
import { ctcAsLpa } from './jobTerms';

/** What happens next for a student with this application, in a sentence: who is waiting on whom. */
export function nextStep(application: Pick<ApplicationResponse, 'status' | 'jobId'>, placement?: Pick<PlacementRecordResponse, 'ctc' | 'joiningDate'> | null): string {
  switch (application.status) {
    case 'SUBMITTED':
      return 'Waiting for the placement officer to review your application.';
    case 'SHORTLISTED':
      return 'You are shortlisted. The placement officer will decide next.';
    case 'SELECTED': {
      const details = [placement && ctcAsLpa(placement.ctc) && `package ${ctcAsLpa(placement.ctc)}`, placement?.joiningDate ? `joining ${placement.joiningDate}` : null].filter(Boolean);
      return details.length > 0 ? `You are selected: ${details.join(', ')}.` : 'You are selected.';
    }
    case 'REJECTED':
      return 'Not taken forward.';
    case 'WITHDRAWN':
      return 'You withdrew this application.';
    default:
      return '';
  }
}

/** Whether the student can still withdraw: only before a final decision. */
export function canWithdraw(status: string): boolean {
  return status === 'SUBMITTED' || status === 'SHORTLISTED';
}

/** "4 applications: 1 selected, 1 shortlisted, 1 waiting, 1 not taken forward" (withdrawn ones are counted in the total only). */
export function applicationsSummary(applications: Array<Pick<ApplicationResponse, 'status'>>): string {
  const n = applications.length;
  if (n === 0) return 'You have not applied to any job yet.';
  const count = (s: string) => applications.filter((a) => a.status === s).length;
  const parts = [
    [count('SELECTED'), 'selected'], [count('SHORTLISTED'), 'shortlisted'], [count('SUBMITTED'), 'waiting for review'], [count('REJECTED'), 'not taken forward'],
  ].filter(([c]) => (c as number) > 0).map(([c, label]) => `${c} ${label}`);
  return `${n} application${n === 1 ? '' : 's'}${parts.length > 0 ? ': ' + parts.join(', ') : ''}.`;
}
