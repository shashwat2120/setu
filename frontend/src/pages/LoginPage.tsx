import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../api/client';
import { passkeysSupported } from '../auth/passkeyLogin';

export default function LoginPage() {
  const { user, login, loginWithPasskey, loading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!loading && user) {
    const from = (location.state as { from?: Location })?.from;
    return <Navigate to={from?.pathname ?? '/app'} replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      navigate('/app', { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 401) setError('Incorrect email or password.');
        else if (err.status === 423) setError('This account is temporarily locked after repeated failed attempts.');
        else if (err.status === 429) setError(err.message || 'Too many attempts. Please wait a moment and try again.');
        else setError(err.message || 'Something went wrong. Please try again.');
      } else {
        setError('Could not reach the server. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function onPasskey() {
    setError(null);
    setSubmitting(true);
    try {
      await loginWithPasskey(email);
      navigate('/app', { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 401) setError('That passkey was not recognised. Use your password, or register a passkey from Security after signing in.');
        else if (err.status === 403) setError(err.message || 'This account cannot sign in.');
        else setError(err.message || 'Passkey sign-in failed. Please try again.');
      } else {
        setError(err instanceof Error ? err.message : 'Passkey sign-in failed. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        background: 'var(--bg)',
        padding: 24,
      }}
    >
      <div style={{ width: '100%', maxWidth: 380 }}>
        <p style={{ fontFamily: 'var(--font-display)', fontSize: 24, fontWeight: 700, marginBottom: 4 }}>Setu</p>
        <h1 className="eyebrow" style={{ marginBottom: 28, fontWeight: 400 }}>Sign in</h1>

        <form onSubmit={onSubmit} noValidate>
          <div className="form-field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="username"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              aria-invalid={error ? true : undefined}
              aria-describedby={error ? 'login-error' : undefined}
            />
          </div>
          <div className="form-field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-invalid={error ? true : undefined}
              aria-describedby={error ? 'login-error' : undefined}
            />
          </div>

          {error && (
            <p id="login-error" role="alert" className="form-error" style={{ marginBottom: 16 }}>
              {error}
            </p>
          )}

          <button type="submit" className="btn btn-primary" style={{ width: '100%', justifyContent: 'center' }} disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
          {passkeysSupported() && (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ width: '100%', justifyContent: 'center', marginTop: 10 }}
              onClick={onPasskey}
              disabled={submitting}
            >
              Sign in with a passkey
            </button>
          )}
          <p style={{ marginTop: 16, fontSize: 13 }}>
            <Link to="/forgot-password">Forgot your password?</Link>
          </p>
        </form>
      </div>
    </main>
  );
}
