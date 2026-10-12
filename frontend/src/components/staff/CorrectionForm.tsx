export type RequestedStatus = 'PRESENT' | 'HALF_DAY' | 'ON_LEAVE';

export interface CorrectionValues {
  date: string;
  status: RequestedStatus;
  inTime: string;
  outTime: string;
  reason: string;
}

interface Props {
  values: CorrectionValues;
  onChange: (patch: Partial<CorrectionValues>) => void;
  onSubmit: () => void;
  busy: boolean;
  message: { ok: boolean; text: string } | null;
  minDate?: string;
  maxDate?: string;
  windowDays: number;
}

/** "Fix a missed check-in or check-out": a compact request that an administrator approves before anything changes. */
export function CorrectionForm({ values, onChange, onSubmit, busy, message, minDate, maxDate, windowDays }: Props) {
  const canSubmit = !!values.date && values.reason.trim().length > 0 && !busy;
  const times = values.status !== 'ON_LEAVE';
  return (
    <section className="staff-card" aria-label="Fix a missed check-in or check-out" id="correction-form">
      <h2 style={{ marginBottom: 2 }}>Fix a missed check-in or check-out</h2>
      <p className="staff-muted" style={{ marginBottom: 10 }}>
        Any day in the last {windowDays} days. An administrator approves it before anything changes.
      </p>
      <form onSubmit={(e) => { e.preventDefault(); if (canSubmit) onSubmit(); }} style={{ display: 'grid', gap: 10 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10 }}>
          <div className="form-field">
            <label htmlFor="reg-date">Day</label>
            <input id="reg-date" type="date" value={values.date} min={minDate} max={maxDate} onChange={(e) => onChange({ date: e.target.value })} required />
          </div>
          <div className="form-field">
            <label htmlFor="reg-status">Should read</label>
            <select id="reg-status" value={values.status} onChange={(e) => onChange({ status: e.target.value as RequestedStatus })}>
              <option value="PRESENT">Present</option>
              <option value="HALF_DAY">Half day</option>
              <option value="ON_LEAVE">On leave</option>
            </select>
          </div>
          <div className="form-field">
            <label htmlFor="reg-in">In / out time</label>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <input id="reg-in" type="time" aria-label="Check-in time" value={values.inTime} disabled={!times} onChange={(e) => onChange({ inTime: e.target.value })} style={{ minWidth: 0, flex: 1 }} />
              <span className="staff-muted">–</span>
              <input id="reg-out" type="time" aria-label="Check-out time" value={values.outTime} disabled={!times} onChange={(e) => onChange({ outTime: e.target.value })} style={{ minWidth: 0, flex: 1 }} />
            </div>
          </div>
        </div>
        <div className="form-field">
          <label htmlFor="reg-reason">Reason</label>
          <textarea id="reg-reason" value={values.reason} onChange={(e) => onChange({ reason: e.target.value })} rows={2} maxLength={500} required placeholder="For example: forgot to check out after the evening lab" />
        </div>
        {message && <p role={message.ok ? 'status' : 'alert'} className={message.ok ? undefined : 'form-error'}>{message.text}</p>}
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <button type="submit" className="btn btn-primary" style={{ padding: '9px 18px', fontSize: 14 }} disabled={!canSubmit}>
            {busy ? 'Sending…' : 'Send for approval'}
          </button>
        </div>
      </form>
    </section>
  );
}
