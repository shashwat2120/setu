/** Shared WebAuthn helpers for the step-up and passkey sign-in ceremonies. */

export function decodeBase64Url(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

export function encodeBase64Url(value: ArrayBuffer): string {
  const bytes = new Uint8Array(value);
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * The assertion in the JSON shape the server's WebAuthn verifier parses. Uses the browser's native
 * `toJSON()` (WebAuthn Level 3) and falls back to building the same shape by hand.
 */
export function assertionToJson(credential: PublicKeyCredential): unknown {
  const response = credential.response as AuthenticatorAssertionResponse;
  const nativeToJSON = (credential as unknown as { toJSON?: () => unknown }).toJSON;
  return nativeToJSON
    ? nativeToJSON.call(credential)
    : {
      id: credential.id,
      rawId: encodeBase64Url(credential.rawId),
      type: credential.type,
      response: {
        clientDataJSON: encodeBase64Url(response.clientDataJSON),
        authenticatorData: encodeBase64Url(response.authenticatorData),
        signature: encodeBase64Url(response.signature),
        userHandle: response.userHandle ? encodeBase64Url(response.userHandle) : null,
      },
      clientExtensionResults: credential.getClientExtensionResults(),
    };
}
