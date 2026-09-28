import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { getAccessToken } from './client';

/**
 * Subscribes to the real backend's `/topic/attendance/section/{sectionId}` STOMP destination while a session
 * is open. The broadcast payload is deliberately a minimal refresh signal, not a roster (see
 * AttendanceRecordService#broadcastUpdate's javadoc) — this hook never trusts the socket payload as data, it
 * only uses it to know when to re-run `onUpdate` (a real REST re-fetch), exactly as the backend intends.
 *
 * Bearer auth happens on the STOMP CONNECT frame's native `Authorization` header — the WebSocket handshake
 * itself carries no HTTP Authorization header a servlet filter would see (see
 * StompAuthorizationChannelInterceptor's javadoc), so this is the one place in the frontend that reads the
 * access token directly instead of going through apiFetch.
 */
export function useAttendanceLiveUpdates(sectionId: string | null, onUpdate: () => void) {
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const onUpdateRef = useRef(onUpdate);
  onUpdateRef.current = onUpdate;

  useEffect(() => {
    if (!sectionId) {
      setConnected(false);
      setError(null);
      return;
    }
    const token = getAccessToken();
    if (!token) {
      setError('Not authenticated.');
      return;
    }

    let failedAttempts = 0;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const client = new Client({
      brokerURL: `${protocol}//${window.location.host}/ws`,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      onConnect: () => {
        failedAttempts = 0;
        setConnected(true);
        setError(null);
        client.subscribe(`/topic/attendance/section/${sectionId}`, () => {
          onUpdateRef.current();
        });
      },
      onStompError: (frame) => {
        setConnected(false);
        setError(frame.headers.message ?? 'Realtime connection was rejected.');
      },
      onWebSocketClose: () => {
        setConnected(false);
        failedAttempts += 1;
        // After a couple of failed handshakes, stop implying it will resolve itself — most likely cause in
        // this dev setup is LiveDevBridge's documented lack of WebSocket upgrade support (see its javadoc).
        if (failedAttempts >= 2) {
          setError('Realtime updates are unavailable right now. The records below still refresh when you take an action.');
        }
      },
    });

    client.activate();
    return () => {
      client.deactivate();
      setConnected(false);
    };
  }, [sectionId]);

  return { connected, error };
}
