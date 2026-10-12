import { apiFetch } from '../api/client';
import { assertionToJson, decodeBase64Url } from './webauthn';

interface AuthenticationOptionsResponse {
  challengeId: string;
  challenge: string;
  rpId: string;
  allowCredentialIds: string[];
  timeoutMillis: number;
}

/** True when this browser can run a WebAuthn assertion at all. */
export function passkeysSupported(): boolean {
  return typeof window !== 'undefined' && !!window.PublicKeyCredential && !!navigator.credentials?.get;
}

/**
 * Sign in with a passkey instead of a password. With an email, the server limits the prompt to that account's
 * credentials; without one, the browser offers whichever discoverable passkey it holds for this site. Returns the
 * access token; the refresh cookie is set by the server, exactly like a password sign-in.
 */
export async function signInWithPasskey(email?: string): Promise<string> {
  if (!passkeysSupported()) throw new Error('This browser does not support passkeys.');

  const options = await apiFetch<AuthenticationOptionsResponse>('/auth/passkey/authenticate/options', {
    method: 'POST',
    body: { email: email?.trim() || null },
    skipAuthRetry: true,
  });

  let credential: PublicKeyCredential | null;
  try {
    credential = await navigator.credentials.get({
      publicKey: {
        challenge: decodeBase64Url(options.challenge),
        rpId: options.rpId,
        timeout: options.timeoutMillis,
        userVerification: 'preferred',
        allowCredentials: options.allowCredentialIds.map((id) => ({ type: 'public-key' as const, id: decodeBase64Url(id) })),
      },
    }) as PublicKeyCredential | null;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotAllowedError') {
      throw new Error('Passkey sign-in was cancelled or timed out.');
    }
    throw error;
  }
  if (!credential) throw new Error('Passkey sign-in was cancelled.');

  const finish = await apiFetch<{ accessToken: string }>('/auth/passkey/authenticate/finish', {
    method: 'POST',
    body: { challengeId: options.challengeId, authenticationResponseJson: JSON.stringify(assertionToJson(credential)) },
    skipAuthRetry: true,
  });
  return finish.accessToken;
}
