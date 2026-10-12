import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiError } from '../api/client';
import { passwordChecks } from '../lib/passwordRules';

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const navigate = useNavigate();

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [touched, setTouched] = useState(false);
  const checks = passwordChecks(password, confirmPassword);
  const allOk = checks.every((c) => c.ok);

  if (!token) {
    return (
      <CenteredCard>
        <p style={{ marginBottom: 16 }}>This reset link is missing its token. Please request a new one.</p>
        <Link to="/forgot-password" className="btn btn-primary">Request a new link</Link>
      </CenteredCard>
    );
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setTouched(true);
    if (!allOk) {
      setError(checks.find((c) => !c.ok)?.id === 'match' ? 'Passwords do not match.' : 'Please choose a password that meets the rules below.');
      return;
    }
    setSubmitting(true);
    try {
      await apiFetch('/auth/password/reset/complete', {
        method: 'POST',
        body: { token, newPassword: password },
        skipAuthRetry: true,
      });
      setDone(true);
      setTimeout(() => navigate('/login', { replace: true }), 2000);
    } catch (err) {
      if (err instanceof ApiError && err.status === 400 && err.message.startsWith('password_too_weak')) {
        // The link is still valid (the server does not consume it), so the person can just try another password.
        setError('Please choose a stronger password: 8 to 128 characters, not a very common password, and not your email address.');
      } else if (err instanceof ApiError && err.status === 400) {
        setError('This reset link is invalid or has expired. Please request a new one.');
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <CenteredCard>
        <p style={{ marginBottom: 16 }}>Your password has been reset. Redirecting to sign in…</p>
        <Link to="/login" className="btn btn-primary">Sign in now</Link>
      </CenteredCard>
    );
  }

  return (
    <CenteredCard>
      <form onSubmit={onSubmit} noValidate>
        <div className="form-field">
          <label htmlFor="password">New password</label>
          <input
            id="password"
            type="password"
            autoComplete="new-password"
            required
            maxLength={256}
            aria-describedby="password-rules"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <div className="form-field">
          <label htmlFor="confirmPassword">Confirm new password</label>
          <input
            id="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
            aria-describedby="password-rules"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
          />
        </div>
        <ul id="password-rules" aria-live="polite" style={{ listStyle: 'none', margin: '0 0 16px', padding: 0, fontSize: 13, display: 'grid', gap: 4 }}>
          {checks.map((c) => (
            <li key={c.id} style={{ color: c.ok ? 'var(--verified)' : touched ? 'var(--flag)' : 'var(--ink-soft)' }}>
              <span aria-hidden="true">{c.ok ? '✓' : '○'}</span> {c.label}
              <span style={{ position: 'absolute', left: -9999 }}>{c.ok ? ' (met)' : ' (not met yet)'}</span>
            </li>
          ))}
        </ul>
        {error && (
          <p role="alert" className="form-error" style={{ marginBottom: 16 }}>
            {error}
          </p>
        )}
        <button type="submit" className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={submitting}>
          {submitting ? 'Resetting…' : 'Reset password'}
        </button>
      </form>
    </CenteredCard>
  );
}

function CenteredCard({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)', padding: 24 }}>
      <div style={{ width: '100%', maxWidth: 380 }}>
        <p style={{ fontFamily: 'var(--font-display)', fontSize: 24, fontWeight: 700, marginBottom: 4 }}>Setu</p>
        <p className="eyebrow" style={{ marginBottom: 28 }}>Reset password</p>
        {children}
      </div>
    </div>
  );
}
