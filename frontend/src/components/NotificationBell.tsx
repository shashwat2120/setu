import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { useAuth } from '../auth/AuthContext';

interface NotificationItem {
  id: string;
  type: string;
  title: string;
  message: string;
  link: string | null;
  createdAt: string;
  readAt: string | null;
}

interface NotificationPage {
  items: NotificationItem[];
  unreadCount: number;
}

const POLL_MS = 30_000;

function timeAgo(iso: string): string {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(iso).toLocaleDateString();
}

/**
 * The bell in the app header: the signed-in person's own notifications (written by the Kafka consumer in the notification module).
 * Polls while the tab is visible; the server decides whose notifications these are, the page sends no user or college id.
 */
export default function NotificationBell() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState<NotificationPage | null>(null);
  const [failed, setFailed] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  const load = useCallback(() => {
    apiFetch<NotificationPage>('/notifications?limit=20')
      .then((p) => {
        // Anything that is not the expected shape (for example an HTML page from a mis-routed proxy) counts as a failure.
        if (!p || !Array.isArray(p.items)) throw new Error('unexpected response');
        setPage(p);
        setFailed(false);
      })
      .catch(() => setFailed(true));
  }, []);

  useEffect(() => {
    load();
    const poll = window.setInterval(() => {
      if (document.visibilityState === 'visible') load();
    }, POLL_MS);
    const onFocus = () => load();
    window.addEventListener('focus', onFocus);
    return () => {
      window.clearInterval(poll);
      window.removeEventListener('focus', onFocus);
    };
  }, [load]);

  useEffect(() => {
    if (!open) return;
    load();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    const onPointer = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onPointer);
    };
  }, [open, load]);

  const unread = page?.unreadCount ?? 0;

  /** Where a notification leads. Interventions go to the advisor's own screen, which depends on their role. */
  function targetFor(n: NotificationItem): string | null {
    if (n.link) return n.link;
    if (n.type === 'INTERVENTION_ASSIGNED') {
      const roles = user?.roles.map((r) => r.roleCode) ?? [];
      if (roles.includes('FACULTY')) return '/app/faculty/student-success';
      if (roles.includes('COLLEGE_ADMIN')) return '/app/admin/student-success';
    }
    return null;
  }

  async function openItem(n: NotificationItem) {
    const target = targetFor(n);
    if (!n.readAt) {
      // Optimistic: the badge drops at once; the next load corrects it if the server disagreed.
      setPage((p) => p && {
        items: p.items.map((i) => (i.id === n.id ? { ...i, readAt: new Date().toISOString() } : i)),
        unreadCount: Math.max(0, p.unreadCount - 1),
      });
      try {
        await apiFetch(`/notifications/${n.id}/read`, { method: 'POST' });
      } catch {
        load();
      }
    }
    if (target) {
      setOpen(false);
      navigate(target);
    }
  }

  async function markAllRead() {
    setPage((p) => p && { items: p.items.map((i) => ({ ...i, readAt: i.readAt ?? new Date().toISOString() })), unreadCount: 0 });
    try {
      await apiFetch('/notifications/read-all', { method: 'POST' });
    } catch {
      load();
    }
  }

  return (
    <div ref={rootRef} className="notif-root">
      <button
        ref={buttonRef}
        type="button"
        className="notif-button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="notif-panel"
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
        onClick={() => setOpen((v) => !v)}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M6 9a6 6 0 1 1 12 0c0 5 2 6.5 2 6.5H4S6 14 6 9Z" />
          <path d="M10 19a2 2 0 0 0 4 0" />
        </svg>
        {unread > 0 && <span className="notif-badge" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
      </button>

      {open && (
        <div id="notif-panel" className="notif-panel" role="dialog" aria-label="Notifications">
          <div className="notif-panel-head">
            <strong>Notifications</strong>
            <button type="button" className="notif-link" onClick={markAllRead} disabled={unread === 0}>
              Mark all read
            </button>
          </div>
          {failed && !page && <p className="notif-empty">Could not load notifications. Try again shortly.</p>}
          {page && page.items.length === 0 && <p className="notif-empty">Nothing new. Updates about your grades, fees and courses will appear here.</p>}
          {page && page.items.length > 0 && (
            <ul className="notif-list">
              {page.items.map((n) => (
                <li key={n.id}>
                  <button type="button" className="notif-item" data-unread={!n.readAt} onClick={() => void openItem(n)}>
                    <span className="notif-title">
                      {!n.readAt && <span className="notif-dot" aria-label="Unread" />}
                      {n.title}
                    </span>
                    <span className="notif-message">{n.message}</span>
                    <span className="notif-time">{timeAgo(n.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
