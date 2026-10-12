import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { apiFetch, setAccessToken } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { useApiQuery } from '../api/useApiQuery';

interface PasskeyResponse { id: string; active: boolean }
interface RegistrationOptionsResponse {
  challengeId: string; challenge: string; rpId: string; rpName: string;
  userHandle: string; userName: string; timeoutMillis: number; residentKey: string;
}
interface RegistrationFinishResponse { passkeyId: string; accessToken: string }

function base64UrlToBuffer(value: string): ArrayBuffer {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const pad = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
  const binary = atob(padded + pad);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function bufferToBase64Url(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Real WebAuthn registration ceremony — no client library, since Chromium's native
 * `PublicKeyCredential.prototype.toJSON()` already produces exactly the JSON shape
 * `PasskeyCeremonyVerifier`'s `webAuthnManager.parseRegistrationResponseJSON` expects
 * (WebAuthn Level 3 spec). A manual fallback covers browsers without `toJSON` yet.
 * <p>
 * This is the real, previously-missing prerequisite for every step-up-gated action in
 * the app (elevated role grants/revocations) — without a registered passkey, step-up
 * can never be completed, regardless of role or permission.
 */
export default function SecurityPage() {
  const { user } = useAuth();
  const passkeys = useApiQuery<PasskeyResponse[]>('/auth/passkey');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const supported = typeof window !== 'undefined' && !!window.PublicKeyCredential;

  const register = async () => {
    if (!user) return;
    setBusy(true); setMessage('');
    try {
      const options = await apiFetch<RegistrationOptionsResponse>('/auth/passkey/register/options', {
        method: 'POST', body: { userId: user.userId },
      });
      const publicKey: PublicKeyCredentialCreationOptions = {
        challenge: base64UrlToBuffer(options.challenge),
        rp: { id: options.rpId, name: options.rpName },
        user: { id: base64UrlToBuffer(options.userHandle), name: options.userName, displayName: options.userName },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
        timeout: options.timeoutMillis,
        authenticatorSelection: { residentKey: options.residentKey as ResidentKeyRequirement, userVerification: 'preferred' },
        attestation: 'none',
      };
      const credential = await navigator.credentials.create({ publicKey }) as PublicKeyCredential | null;
      if (!credential) throw new Error('No credential was created.');
      const response = credential.response as AuthenticatorAttestationResponse;
      const nativeToJSON = (credential as unknown as { toJSON?: () => unknown }).toJSON;
      const payload = nativeToJSON
        ? nativeToJSON.call(credential)
        : {
          id: credential.id,
          rawId: bufferToBase64Url(credential.rawId),
          type: credential.type,
          response: {
            clientDataJSON: bufferToBase64Url(response.clientDataJSON),
            attestationObject: bufferToBase64Url(response.attestationObject),
          },
          clientExtensionResults: credential.getClientExtensionResults(),
        };
      const finish = await apiFetch<RegistrationFinishResponse>('/auth/passkey/register/finish', {
        method: 'POST',
        body: { userId: user.userId, challengeId: options.challengeId, registrationResponseJson: JSON.stringify(payload) },
      });
      setAccessToken(finish.accessToken);
      setMessage('Passkey registered. You can now complete step-up re-authentication for sensitive actions.');
      passkeys.refetch();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Could not register a passkey.');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (passkeyId: string) => {
    setBusy(true); setMessage('');
    try {
      await apiFetch(`/auth/passkey/${passkeyId}`, { method: 'DELETE' });
      passkeys.refetch();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Could not revoke this passkey.'); }
    finally { setBusy(false); }
  };

  return (
    <div>
      <PageHeader
        title="Security"
        subtitle="Register a passkey to complete step-up re-authentication for sensitive actions (elevated role grants, revocations)."
      />
      {!supported && (
        <div className="card" style={{ marginBottom: 16 }}>
          <p style={{ fontSize: 13, color: 'var(--flag)' }}>This browser does not support passkeys (WebAuthn).</p>
        </div>
      )}
      <div className="card" style={{ marginBottom: 16, maxWidth: 480 }}>
        <h2 style={{ fontSize: 16, marginBottom: 10 }}>Your passkeys</h2>
        {passkeys.data && passkeys.data.length > 0 ? (
          passkeys.data.map((p) => (
            <div key={p.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderTop: '1px solid var(--line)', padding: '10px 0' }}>
              <span className="mono" style={{ fontSize: 12 }}>{p.id.slice(0, 8)}… {p.active ? '· Active' : '· Revoked'}</span>
              {p.active && (
                <button type="button" className="btn btn-ghost" style={{ padding: '4px 10px', fontSize: 12 }} disabled={busy} onClick={() => revoke(p.id)}>
                  Revoke
                </button>
              )}
            </div>
          ))
        ) : <p style={{ fontSize: 13, color: 'var(--ink-soft)' }}>No passkeys registered yet.</p>}
      </div>
      {message && <p role="status" style={{ marginBottom: 12, fontSize: 13 }}>{message}</p>}
      <button type="button" className="btn btn-primary" disabled={busy || !supported} onClick={register}>
        {busy ? 'Registering…' : 'Register a new passkey'}
      </button>
    </div>
  );
}
