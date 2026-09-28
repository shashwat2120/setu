import { useState } from 'react';
import { apiFetch, ApiError } from '../../api/client';
import { useApiQuery } from '../../api/useApiQuery';
import type { CollegeResponse, RoleAssignResponse } from '../../api/types';
import { PageHeader } from '../../components/ui/PageHeader';

const ROLE_CODES = ['SUPER_ADMIN', 'COLLEGE_ADMIN', 'FACULTY', 'STUDENT', 'PLACEMENT_OFFICER', 'GUARDIAN'] as const;

const inputStyle: React.CSSProperties = {
  width: '100%',
  fontFamily: 'var(--font-body)',
  fontSize: 14,
  padding: '9px 12px',
  border: '1px solid var(--rule)',
  borderRadius: 4,
  background: 'var(--bg-elev)',
  color: 'var(--ink)',
};

const labelStyle: React.CSSProperties = { display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 };

export default function SuperAdminRolesPage() {
  const colleges = useApiQuery<CollegeResponse[]>('/institution/colleges');
  const [targetUserId, setTargetUserId] = useState('');
  const [roleCode, setRoleCode] = useState<typeof ROLE_CODES[number]>('COLLEGE_ADMIN');
  const [tenantId, setTenantId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [revokeUserRoleId, setRevokeUserRoleId] = useState('');
  const [revoking, setRevoking] = useState(false);
  const [revokeResult, setRevokeResult] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const assign = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setResult(null);
    try {
      const response = await apiFetch<RoleAssignResponse>('/identity/roles', {
        method: 'POST',
        body: { targetUserId, roleCode, tenantId: roleCode === 'SUPER_ADMIN' ? null : tenantId || null },
      });
      setResult(`Role assigned (assignment ID ${response.userRoleId}).`);
      setTargetUserId('');
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        setError('Denied. This action requires a fresh passkey step-up re-authentication (completed via /auth/step-up). '
          + 'This demo environment’s seeded accounts are password-only with no registered passkey, so step-up cannot '
          + 'be completed here — this is an environment limitation, not confirmation that the permission itself is missing.');
      } else {
        setError(err instanceof Error ? err.message : 'Could not assign the role.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const revoke = async (e: React.FormEvent) => {
    e.preventDefault();
    setRevoking(true);
    setRevokeError(null);
    setRevokeResult(null);
    try {
      await apiFetch(`/identity/roles/${revokeUserRoleId}/revoke`, { method: 'POST' });
      setRevokeResult('Role assignment revoked.');
      setRevokeUserRoleId('');
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        setRevokeError('Denied. This action requires a fresh passkey step-up re-authentication, which this demo '
          + 'environment’s password-only accounts cannot complete — an environment limitation, not a confirmed permission gap.');
      } else if (err instanceof ApiError && err.status === 404) {
        setRevokeError('No such role assignment.');
      } else {
        setRevokeError(err instanceof Error ? err.message : 'Could not revoke the role.');
      }
    } finally {
      setRevoking(false);
    }
  };

  return (
    <div>
      <PageHeader title="Role assignment" subtitle="Grant or revoke a platform role. Both actions require step-up re-authentication." />

      <div className="card" style={{ marginBottom: 24, maxWidth: 640, borderColor: 'var(--brass)' }}>
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', margin: 0 }}>
          There is deliberately no platform-wide user search (a person directory would itself be a PII leak
          surface). Enter the target account&apos;s user ID directly — it can be read from a College
          Admin&apos;s student or faculty detail view, or from a registration response. Both endpoints below
          delegate to the same step-up-gated <code>RoleAssignmentService</code> the backend has always had.
        </p>
      </div>

      <form onSubmit={assign} className="card" style={{ marginBottom: 24, maxWidth: 480 }}>
        <h2 style={{ fontSize: 16, marginBottom: 14 }}>Assign a role</h2>
        <div style={{ marginBottom: 12 }}>
          <label htmlFor="target-user-id" style={labelStyle}>Target user ID</label>
          <input id="target-user-id" required value={targetUserId} onChange={(e) => setTargetUserId(e.target.value)}
            style={inputStyle} placeholder="UUID" />
        </div>
        <div style={{ marginBottom: 12 }}>
          <label htmlFor="role-code" style={labelStyle}>Role</label>
          <select id="role-code" value={roleCode} onChange={(e) => setRoleCode(e.target.value as typeof ROLE_CODES[number])} style={inputStyle}>
            {ROLE_CODES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </div>
        {roleCode !== 'SUPER_ADMIN' && (
          <div style={{ marginBottom: 16 }}>
            <label htmlFor="role-tenant" style={labelStyle}>College</label>
            <select id="role-tenant" required value={tenantId} onChange={(e) => setTenantId(e.target.value)} style={inputStyle}>
              <option value="">Select a college…</option>
              {colleges.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}
        {error && <p style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{error}</p>}
        {result && <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>{result}</p>}
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Assigning…' : 'Assign role'}
        </button>
      </form>

      <form onSubmit={revoke} className="card" style={{ maxWidth: 480 }}>
        <h2 style={{ fontSize: 16, marginBottom: 14 }}>Revoke a role assignment</h2>
        <div style={{ marginBottom: 16 }}>
          <label htmlFor="revoke-id" style={labelStyle}>Assignment ID</label>
          <input id="revoke-id" required value={revokeUserRoleId} onChange={(e) => setRevokeUserRoleId(e.target.value)}
            style={inputStyle} placeholder="UUID" />
        </div>
        {revokeError && <p style={{ color: 'var(--flag)', fontSize: 13, marginBottom: 12 }}>{revokeError}</p>}
        {revokeResult && <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 12 }}>{revokeResult}</p>}
        <button type="submit" className="btn btn-danger" disabled={revoking}>
          {revoking ? 'Revoking…' : 'Revoke assignment'}
        </button>
      </form>
    </div>
  );
}
