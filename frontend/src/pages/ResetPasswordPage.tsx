import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { apiFetch, ApiError } from '../api/client';
import { isPlausibleEmail, isValidResetCode, passwordChecks, RESET_CODE_LENGTH, RESET_CODE_MINUTES } from '../lib/passwordRules';

const RESEND_COOLDOWN_SECONDS = 30;

/** Sets a new password with the six-digit code that was emailed. The email address arrives in router state from the forgot-password page. */
export default function ResetPasswordPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const initialEmail = (location.state as { email?: string } | null)?.email ?? '';

  const [email, setEmail] = useState(initialEmail);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [done, setDone] = useState(false);
  const [touched, setTouched] = useState(false);
  const checks = passwordChecks(password, confirmPassword);
  const allPasswordRulesOk = checks.every((c) => c.ok);

  useEffect(() => {
    if (cooldown <= 0) return undefined;
    const id = window.setTimeout(() => setCooldown((s) => s - 1), 1000);
    return () => window.clearTimeout(id);
  }, [cooldown]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setInfo(null);
    setTouched(true);
    if (!isPlausibleEmail(email)) {
      setError('Enter the email address the code was sent to.');
      return;
    }
    if (!isValidResetCode(code)) {
      setError(`Enter the ${RESET_CODE_LENGTH}-digit code from the email.`);
      return;
    }
    if (!allPasswordRulesOk) {
      setError(checks.find((c) => !c.ok)?.id === 'match' ? 'Passwords do not match.' : 'Please choose a password that meets the rules below.');
      return;
    }
    setSubmitting(true);
    try {
      await apiFetch('/auth/password/reset/confirm', {
        method: 'POST',
        body: { email: email.trim(), code, newPassword: password },
        skipAuthRetry: true,
      });
      setDone(true);
      setTimeout(() => navigate('/login', { replace: true }), 2000);
    } catch (err) {
      if (err instanceof ApiError && err.status === 400 && err.message.startsWith('password_too_weak')) {
        // The code is still valid (the server does not use it up for a weak password), so the person can just try another password.
        setError('Please choose a stronger password: 8 to 128 characters, not a very common password, and not your email address.');
      } else if (err instanceof ApiError && err.status === 400) {
        setError('That code is wrong or has expired. Check the newest email we sent, or request a new code. A code stops working after 5 wrong tries.');
      } else if (err instanceof ApiError && err.status === 429) {
        setError('Too many attempts. Please wait a few minutes, then try again.');
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function resend() {
    setError(null);
    setInfo(null);
    if (!isPlausibleEmail(email)) {
      setError('Enter your email address first, then ask for a new code.');
      return;
    }
    setResending(true);
    try {
      await apiFetch('/auth/password/reset/initiate', { method: 'POST', body: { email: email.trim() }, skipAuthRetry: true });
      // Same wording whether or not an account exists, like the forgot-password page.
      setInfo(`If an account exists for ${email.trim()}, we've emailed a new code. Older codes no longer work.`);
      setCode('');
      setCooldown(RESEND_COOLDOWN_SECONDS);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 429 ? 'Too many requests. Please wait a few minutes, then try again.' : 'Something went wrong. Please try again.');
    } finally {
      setResending(false);
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
        <p style={{ marginBottom: 16, fontSize: 14, color: 'var(--ink-soft)' }}>
          Enter the {RESET_CODE_LENGTH}-digit code we emailed you. It expires in {RESET_CODE_MINUTES} minutes and works once.
        </p>
        <div className="form-field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div className="form-field">
          <label htmlFor="code">Code</label>
          <input
            id="code"
            inputMode="numeric"
            autoComplete="one-time-code"
            required
            maxLength={RESET_CODE_LENGTH}
            placeholder="123456"
            value={code}
            // Pasting "482 916" or "482-916" still works: only the digits are kept.
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, RESET_CODE_LENGTH))}
            style={{ fontFamily: 'var(--font-mono)', letterSpacing: 6, fontSize: 18 }}
          />
          <p style={{ margin: '6px 0 0', fontSize: 13 }}>
            Didn't get it, or it stopped working?{' '}
            <button type="button" className="staff-link" onClick={resend} disabled={resending || cooldown > 0}>
              {resending ? 'Sending…' : cooldown > 0 ? `Send a new code (${cooldown}s)` : 'Send a new code'}
            </button>
          </p>
        </div>
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
        {info && (
          <p role="status" style={{ marginBottom: 16, fontSize: 14, color: 'var(--verified)' }}>
            {info}
          </p>
        )}
        {error && (
          <p role="alert" className="form-error" style={{ marginBottom: 16 }}>
            {error}
          </p>
        )}
        <button type="submit" className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={submitting}>
          {submitting ? 'Resetting…' : 'Reset password'}
        </button>
        <p style={{ marginTop: 16, fontSize: 13 }}>
          <Link to="/login">Back to sign in</Link>
        </p>
      </form>
    </CenteredCard>
  );
}

function CenteredCard({ children }: { children: React.ReactNode }) {
  return (
    <main style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)', padding: 24 }}>
      <div style={{ width: '100%', maxWidth: 380 }}>
        <p style={{ fontFamily: 'var(--font-display)', fontSize: 24, fontWeight: 700, marginBottom: 4 }}>Setu</p>
        <h1 className="eyebrow" style={{ marginBottom: 28, fontWeight: 400 }}>Reset password</h1>
        {children}
      </div>
    </main>
  );
}
