import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiError } from '../api/client';

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const navigate = useNavigate();

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

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
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
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
      if (err instanceof ApiError && err.status === 400) {
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
            minLength={8}
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
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
          />
        </div>
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
