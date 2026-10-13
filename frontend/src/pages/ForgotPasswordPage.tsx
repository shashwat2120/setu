import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { isPlausibleEmail, RESET_CODE_LENGTH, RESET_CODE_MINUTES } from '../lib/passwordRules';

/**
 * Always shows the same success state regardless of whether the email exists — the backend's own
 * non-enumeration contract (identical response either way) only holds if this page doesn't undermine it by,
 * say, showing a different message for "invalid email" vs "code sent."
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [status, setStatus] = useState<'idle' | 'submitting' | 'sent' | 'error'>('idle');
  const [emailError, setEmailError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    // A shape check only ("name@domain.tld"): whether the address belongs to an account is never revealed here.
    if (!isPlausibleEmail(email)) {
      setEmailError('Enter a valid email address, like name@college.edu.');
      return;
    }
    setEmailError(null);
    setStatus('submitting');
    try {
      await apiFetch('/auth/password/reset/initiate', { method: 'POST', body: { email: email.trim() }, skipAuthRetry: true });
      setStatus('sent');
    } catch {
      // Network/server failure only — never reachable for "email doesn't exist," which the backend answers
      // identically to success.
      setStatus('error');
    }
  }

  return (
    <main style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)', padding: 24 }}>
      <div style={{ width: '100%', maxWidth: 380 }}>
        <p style={{ fontFamily: 'var(--font-display)', fontSize: 24, fontWeight: 700, marginBottom: 4 }}>Setu</p>
        <h1 className="eyebrow" style={{ marginBottom: 28, fontWeight: 400 }}>Forgot password</h1>

        {status === 'sent' ? (
          <div>
            <p style={{ marginBottom: 16 }}>
              If an account exists for <strong>{email.trim()}</strong>, we've emailed a {RESET_CODE_LENGTH}-digit code to it. The
              code expires in {RESET_CODE_MINUTES} minutes and works once.
            </p>
            {/* The address travels in router state, never in the URL. */}
            <Link to="/reset-password" state={{ email: email.trim() }} className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }}>
              Enter the code
            </Link>
            <p style={{ marginTop: 16, fontSize: 13 }}>
              <Link to="/login">Back to sign in</Link>
            </p>
          </div>
        ) : (
          <form onSubmit={onSubmit} noValidate>
            <p style={{ marginBottom: 16, fontSize: 14, color: 'var(--ink-soft)' }}>
              Enter the email address of your account and we'll email you a {RESET_CODE_LENGTH}-digit code to choose a new password.
            </p>
            <div className="form-field">
              <label htmlFor="email">Email</label>
              <input
                id="email"
                type="email"
                autoComplete="username"
                required
                value={email}
                aria-invalid={emailError ? true : undefined}
                aria-describedby={emailError ? 'email-error' : undefined}
                onChange={(e) => { setEmail(e.target.value); if (emailError) setEmailError(null); }}
              />
              {emailError && <p id="email-error" role="alert" className="form-error" style={{ marginTop: 6 }}>{emailError}</p>}
            </div>
            {status === 'error' && (
              <p role="alert" className="form-error" style={{ marginBottom: 16 }}>
                Something went wrong. Please try again.
              </p>
            )}
            <button type="submit" className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={status === 'submitting'}>
              {status === 'submitting' ? 'Sending…' : 'Send code'}
            </button>
            <p style={{ marginTop: 16, fontSize: 13 }}>
              <Link to="/login">Back to sign in</Link>
            </p>
          </form>
        )}
      </div>
    </main>
  );
}
