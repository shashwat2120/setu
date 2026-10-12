/**
 * The basic password rules the reset page shows while the person types. They mirror the server's PasswordPolicy so the page can
 * explain problems early, but the server stays the authority: it also rejects very common passwords, which are not listed here.
 */
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 128;

export interface PasswordCheck {
  id: 'length' | 'match';
  label: string;
  ok: boolean;
}

/** The checks the person can see: length within bounds, and that the confirmation matches. */
export function passwordChecks(password: string, confirm: string): PasswordCheck[] {
  const length = [...password].length; // count characters, not UTF-16 units, as the server does
  return [
    { id: 'length', label: `${PASSWORD_MIN} to ${PASSWORD_MAX} characters`, ok: length >= PASSWORD_MIN && length <= PASSWORD_MAX },
    { id: 'match', label: 'Both entries match', ok: password.length > 0 && password === confirm },
  ];
}

/** True when the person's own email address (or its part before the @) was typed as the password. */
export function looksLikeEmail(password: string, email: string | null | undefined): boolean {
  if (!email) return false;
  const p = password.toLowerCase();
  const e = email.toLowerCase();
  const local = e.includes('@') ? e.slice(0, e.indexOf('@')) : e;
  return p === e || (local.length >= 4 && p === local);
}

/** A basic shape check ("something@something.something"); the server decides whether an address really exists. */
export function isPlausibleEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim());
}
