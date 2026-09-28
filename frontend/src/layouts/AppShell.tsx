import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { NAV_BY_ROLE } from './navConfig';
import '../styles/app.css';

export default function AppShell() {
  const { user, logout } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);

  const items = user?.roles.flatMap((r) => NAV_BY_ROLE[r.roleCode] ?? []) ?? [];
  const seen = new Set<string>();
  const uniqueItems = items.filter((item) => (seen.has(item.to) ? false : (seen.add(item.to), true)));

  return (
    <div className="app-shell">
      <aside className="app-sidebar" data-open={mobileOpen} aria-label="Primary navigation">
        <div className="app-sidebar-brand">Setu</div>
        <nav>
          {uniqueItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end
              className="app-nav-link"
              onClick={() => setMobileOpen(false)}
              aria-current={undefined}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      <div className="app-main">
        <header className="app-header">
          <button
            type="button"
            className="app-mobile-toggle"
            aria-label={mobileOpen ? 'Close navigation' : 'Open navigation'}
            aria-expanded={mobileOpen}
            onClick={() => setMobileOpen((v) => !v)}
          >
            ☰
          </button>
          <a href="#app-main-content" className="skip-link">Skip to content</a>
          <div style={{ flex: 1 }} />
          <UserMenu name={user?.fullName ?? user?.email ?? 'Account'} onLogout={logout} />
        </header>
        <main id="app-main-content" className="app-content" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function UserMenu({ name, onLogout }: { name: string; onLogout: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div style={{ position: 'relative' }}>
      <button
        type="button"
        className="btn btn-ghost"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        style={{ padding: '8px 14px', fontSize: 13 }}
      >
        {name}
      </button>
      {open && (
        <div
          role="menu"
          style={{
            position: 'absolute',
            right: 0,
            top: '110%',
            background: 'var(--bg-elev)',
            border: '1px solid var(--rule)',
            borderRadius: 6,
            minWidth: 160,
            boxShadow: '0 8px 24px var(--shadow-ink)',
            zIndex: 50,
          }}
        >
          <button
            role="menuitem"
            type="button"
            onClick={onLogout}
            style={{
              width: '100%',
              textAlign: 'left',
              padding: '10px 14px',
              background: 'transparent',
              border: 'none',
              fontSize: 14,
              fontWeight: 600,
              color: 'var(--ink)',
            }}
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
