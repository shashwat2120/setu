import { apiFetch, setAccessToken } from '../api/client';
import { assertionToJson, decodeBase64Url } from './webauthn';

interface StepUpOptionsResponse {
  challengeId: string;
  challenge: string;
  rpId: string;
  timeoutMillis: number;
}

/** Complete the authenticated user's WebAuthn step-up and replace the in-memory access token. */
export async function completeStepUp(): Promise<void> {
  if (!navigator.credentials?.get) throw new Error('This browser cannot complete passkey verification.');

  const options = await apiFetch<StepUpOptionsResponse>('/auth/step-up/options', { method: 'POST' });
  let credential: PublicKeyCredential | null;
  try {
    credential = await navigator.credentials.get({
      publicKey: {
        challenge: decodeBase64Url(options.challenge),
        rpId: options.rpId,
        timeout: options.timeoutMillis,
        userVerification: 'preferred',
      },
    }) as PublicKeyCredential | null;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotAllowedError') {
      throw new Error('Passkey verification was cancelled or timed out. Check that you have an active passkey registered, then try again.');
    }
    throw error;
  }
  if (!credential) throw new Error('Passkey verification was cancelled.');

  const finish = await apiFetch<{ accessToken: string }>('/auth/step-up/finish', {
    method: 'POST',
    body: { challengeId: options.challengeId, authenticationResponseJson: JSON.stringify(assertionToJson(credential)) },
  });
  setAccessToken(finish.accessToken);
}
