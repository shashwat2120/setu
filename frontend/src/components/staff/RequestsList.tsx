import type { RegularisationResponse } from '../../api/types';
import { shortDate } from '../../lib/staffCalendar';
import { StatusBadge } from '../ui/StatusBadge';

interface Props {
  requests: RegularisationResponse[] | null;
  error: Error | null;
  onWithdraw: (id: string) => void;
}

const time = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : null);

const KIND: Record<RegularisationResponse['requestedStatus'], string> = {
  PRESENT: 'Marked present',
  HALF_DAY: 'Half day',
  ON_LEAVE: 'On leave',
};

const STATUS_LABEL: Record<RegularisationResponse['status'], string> = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
};

/** Compact list of the member's own requests with the administrator's decision and note. */
export function RequestsList({ requests, error, onWithdraw }: Props) {
  return (
    <section className="staff-card" aria-label="Your requests">
      <div className="staff-card-head">
        <h2>Your requests</h2>
      </div>
      {error ? (
        <p role="alert" className="form-error">Could not load your requests. {error.message}</p>
      ) : !requests ? (
        <p className="staff-muted">Loading…</p>
      ) : requests.length === 0 ? (
        <p className="staff-muted">No requests yet. When you ask for a day to be corrected, it shows up here with the decision.</p>
      ) : (
        <div>
          {requests.slice(0, 6).map((r) => {
            const range = time(r.requestedCheckInAt) ? `${time(r.requestedCheckInAt)} – ${time(r.requestedCheckOutAt) ?? '…'}` : null;
            return (
              <div className="req-row" key={r.id}>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>{shortDate(r.attendanceDate)}</div>
                  <div className="staff-muted">{KIND[r.requestedStatus]}{range ? ` · ${range}` : ''}</div>
                  {r.decisionNote && <div className="staff-muted" style={{ fontStyle: 'italic' }}>“{r.decisionNote}”</div>}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 4, flexShrink: 0 }}>
                  <StatusBadge value={r.status} label={STATUS_LABEL[r.status]} />
                  {r.status === 'PENDING' && <button type="button" className="staff-link" onClick={() => onWithdraw(r.id)}>Withdraw</button>}
                </div>
              </div>
            );
          })}
          {requests.length > 6 && <p className="staff-muted" style={{ marginTop: 6 }}>Showing your 6 most recent of {requests.length}.</p>}
        </div>
      )}
    </section>
  );
}
