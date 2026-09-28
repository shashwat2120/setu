import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';

/**
 * Always shows the same success state regardless of whether the email exists — the backend's own
 * non-enumeration contract (identical response either way) only holds if this page doesn't undermine it by,
 * say, showing a different message for "invalid email" vs "email sent."
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [status, setStatus] = useState<'idle' | 'submitting' | 'sent' | 'error'>('idle');

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setStatus('submitting');
    try {
      await apiFetch('/auth/password/reset/initiate', { method: 'POST', body: { email }, skipAuthRetry: true });
      setStatus('sent');
    } catch {
      // Network/server failure only — never reachable for "email doesn't exist," which the backend answers
      // identically to success.
      setStatus('error');
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)', padding: 24 }}>
      <div style={{ width: '100%', maxWidth: 380 }}>
        <p style={{ fontFamily: 'var(--font-display)', fontSize: 24, fontWeight: 700, marginBottom: 4 }}>Setu</p>
        <p className="eyebrow" style={{ marginBottom: 28 }}>Forgot password</p>

        {status === 'sent' ? (
          <div>
            <p style={{ marginBottom: 16 }}>
              If an account exists for <strong>{email}</strong>, we've sent a password reset link to it. The
              link expires in 30 minutes.
            </p>
            <Link to="/login" className="btn btn-ghost">Back to sign in</Link>
          </div>
        ) : (
          <form onSubmit={onSubmit} noValidate>
            <div className="form-field">
              <label htmlFor="email">Email</label>
              <input
                id="email"
                type="email"
                autoComplete="username"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
            {status === 'error' && (
              <p role="alert" className="form-error" style={{ marginBottom: 16 }}>
                Something went wrong. Please try again.
              </p>
            )}
            <button type="submit" className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={status === 'submitting'}>
              {status === 'submitting' ? 'Sending…' : 'Send reset link'}
            </button>
            <p style={{ marginTop: 16, fontSize: 13 }}>
              <Link to="/login">Back to sign in</Link>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
