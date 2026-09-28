const BAND_CLASS: Record<string, string> = {
  LOW: 'badge-low',
  MEDIUM: 'badge-medium',
  HIGH: 'badge-high',
  ACTIVE: 'badge-active',
  REVOKED: 'badge-revoked',
  EXPIRED: 'badge-expired',
  SUCCEEDED: 'badge-ok',
  FAILED: 'badge-danger',
  PENDING: 'badge-warn',
  OVERDUE: 'badge-danger',
  REFUNDED: 'badge-warn',
  // Course section / assessment lifecycle
  DRAFT: 'badge-medium',
  SCHEDULED: 'badge-medium',
  OPEN: 'badge-active',
  CLOSED: 'badge-medium',
  PUBLISHED: 'badge-active',
  VOIDED: 'badge-danger',
  CANCELLED: 'badge-danger',
  // Enrollment
  REQUESTED: 'badge-medium',
  ENROLLED: 'badge-active',
  WAITLISTED: 'badge-medium',
  DROPPED: 'badge-danger',
  COMPLETED: 'badge-active',
  REJECTED: 'badge-danger',
  // Grade
  SUPERSEDED: 'badge-medium',
  // Finance
  PAID: 'badge-ok',
  WAIVED: 'badge-medium',
  INITIATED: 'badge-medium',
  INACTIVE: 'badge-medium',
  // Career
  SUBMITTED: 'badge-medium',
  SHORTLISTED: 'badge-medium',
  SELECTED: 'badge-active',
  WITHDRAWN: 'badge-medium',
  // Risk / student success
  PLANNED: 'badge-medium',
  IN_PROGRESS: 'badge-active',
  PROPOSED: 'badge-medium',
  ACCEPTED: 'badge-active',
  DISMISSED: 'badge-medium',
  // Institution / calendar
  SUSPENDED: 'badge-danger',
  ARCHIVED: 'badge-medium',
  CURRENT: 'badge-active',
  REGISTRATION_OPEN: 'badge-active',
  REGISTRATION_CLOSED: 'badge-medium',
  // Enrollment override / waitlist
  USED: 'badge-medium',
  WAITING: 'badge-medium',
  OFFERED: 'badge-medium',
  CONVERTED: 'badge-active',
};

export function StatusBadge({ value, label }: { value: string; label?: string }) {
  const cls = BAND_CLASS[value.toUpperCase()] ?? 'badge-medium';
  return <span className={`badge ${cls}`}>{label ?? value}</span>;
}
