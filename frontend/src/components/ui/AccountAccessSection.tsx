import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch, ApiError } from '../../api/client';
import { completeStepUp } from '../../auth/stepUp';
import { useApiQuery } from '../../api/useApiQuery';
import { ConfirmActionButton } from './ConfirmActionButton';
import type { AccountStatus } from '../../api/types';

interface StatusResponse { status: AccountStatus }

/** Account-level access (can this person sign in at all) — deliberately separate from any profile/academic/employment status shown elsewhere on the same page. See backend UserAccessService. */
export function AccountAccessSection({ userId, tenantId }: { userId: string | null; tenantId: string }) {
  const statusQuery = useApiQuery<StatusResponse>(
    userId ? `/identity/users/${userId}/status?tenantId=${tenantId}` : null, [userId, tenantId],
  );
  const [message, setMessage] = useState('');

  if (!userId) return null;

  const act = async (action: 'suspend' | 'reactivate' | 'deactivate', reason: string) => {
    setMessage('');
    try {
      await completeStepUp();
      await apiFetch(`/identity/users/${userId}/${action}`, { method: 'POST', body: { tenantId, reason } });
      setMessage(`Account ${action}d.`);
      statusQuery.refetch();
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) setMessage('Access denied. Your account needs the required permission and a valid passkey step-up.');
      else setMessage(e instanceof Error ? e.message : `Could not ${action} this account.`);
    }
  };

  const status = statusQuery.data?.status;

  return (
    <section className="card" style={{ marginTop: 20, maxWidth: 480 }}>
      <h2 style={{ fontSize: 16, marginBottom: 6 }}>Account access</h2>
      <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>
        Whether this person can sign in at all — independent of their academic/employment status above. Neither
        one changes the other; suspending here does not withdraw them, and withdrawing them does not suspend this.
      </p>
      <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>
        Each account-access change requires you to verify with your passkey. <Link to="/app/security">Set up a passkey in Security</Link> first if needed.
      </p>
      {status && <p style={{ marginBottom: 10, fontSize: 13 }}>Current account status: <strong>{status}</strong></p>}
      {message && <p role="status" style={{ marginBottom: 10, fontSize: 13 }}>{message}</p>}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {status === 'ACTIVE' && (
          <ConfirmActionButton label="Suspend account" variant="danger"
            confirmMessage="Suspend this account? All active sessions will be revoked immediately and they will not be able to sign in until reactivated."
            onConfirm={() => act('suspend', 'Suspended by administrator')} />
        )}
        {status === 'SUSPENDED' && (
          <ConfirmActionButton label="Reactivate account"
            confirmMessage="Reactivate this account? They will be able to sign in again."
            onConfirm={() => act('reactivate', 'Reactivated by administrator')} />
        )}
        {(status === 'ACTIVE' || status === 'SUSPENDED') && (
          <ConfirmActionButton label="Deactivate account" variant="danger"
            confirmMessage="Deactivate this account? This is terminal — it can never be reactivated, and all active sessions will be revoked immediately."
            onConfirm={() => act('deactivate', 'Deactivated by administrator')} />
        )}
        {status === 'DEACTIVATED' && (
          <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>This account is deactivated. Deactivation is terminal — it cannot be reversed.</p>
        )}
      </div>
    </section>
  );
}
