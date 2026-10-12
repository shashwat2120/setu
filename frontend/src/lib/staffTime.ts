import { useEffect, useRef, useState } from 'react';

/** 485 -> "8h 5m"; 0 -> "0m". */
export function formatMinutes(total: number): string {
  const m = Math.max(0, Math.floor(total)); // floor, to agree with the server (minutes are never rounded up)
  const h = Math.floor(m / 60);
  const r = m % 60;
  return h > 0 ? `${h}h ${r}m` : `${r}m`;
}

/**
 * A ticking "now" that follows the SERVER's clock, not the browser's: the offset between the server's `serverTime` and this machine's
 * clock is captured whenever fresh server data arrives, so a wrong or tampered local clock cannot change what the widget shows.
 */
export function useServerNow(serverTimeIso: string | undefined): number {
  const offsetRef = useRef(0);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (serverTimeIso) {
      offsetRef.current = Date.parse(serverTimeIso) - Date.now();
      setNow(Date.now() + offsetRef.current);
    }
  }, [serverTimeIso]);

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now() + offsetRef.current), 1000);
    return () => window.clearInterval(id);
  }, []);

  return now;
}

/** The time of day in the college's own timezone, e.g. "12:48:19 PM". */
export function formatClock(ms: number, timeZone: string): string {
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone }).format(ms);
}

/** The date in the college's own timezone, e.g. "Tue, 06 Oct 2026". */
export function formatLongDate(ms: number, timeZone: string): string {
  return new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric', timeZone }).format(ms);
}

/** "09:00" -> "9:00 AM" for plain-language policy text. */
export function formatShiftTime(hhmm: string): string {
  const [h, m] = hhmm.split(':').map(Number);
  const d = new Date(2000, 0, 1, h, m);
  return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

/** 483 -> "8h 03m" (always two-digit minutes, the way a timesheet reads). */
export function formatHm(total: number): string {
  const m = Math.max(0, Math.floor(total));
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
}

/** Just the clock's hour and minute, "5:51", and the AM/PM part separately so the page can set them at different sizes. */
export function clockParts(ms: number, timeZone: string): { hm: string; period: string } {
  const parts = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit', hour12: true, timeZone }).formatToParts(ms);
  const hm = `${parts.find((p) => p.type === 'hour')?.value ?? ''}:${parts.find((p) => p.type === 'minute')?.value ?? ''}`;
  return { hm, period: parts.find((p) => p.type === 'dayPeriod')?.value ?? '' };
}

/** 623 seconds -> "0h 10m 23s": a running timer that visibly ticks every second. */
export function formatHms(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  return `${Math.floor(s / 3600)}h ${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}m ${String(s % 60).padStart(2, '0')}s`;
}
