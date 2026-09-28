import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import { ScrollTrigger } from "../lib/gsap";

const TABS = [
  { id: "admin", label: "Admin" },
  { id: "faculty", label: "Faculty" },
  { id: "student", label: "Student" },
  { id: "guardian", label: "Guardian" },
  { id: "super-admin", label: "Super Admin" },
  { id: "placement", label: "Placement" },
];

export default function Nav() {
  const [active, setActive] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const navRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const triggers = TABS.map(({ id }) => {
      const el = document.getElementById(id);
      if (!el) return null;
      return ScrollTrigger.create({
        trigger: el,
        start: "top 50%",
        end: "bottom 50%",
        onEnter: () => setActive(id),
        onEnterBack: () => setActive(id),
      });
    });
    return () => triggers.forEach((t) => t?.kill());
  }, []);

  return (
    <nav
      ref={navRef}
      style={{
        position: "sticky",
        top: "env(safe-area-inset-top, 0px)",
        zIndex: 50,
        background: "color-mix(in srgb, var(--bg) 88%, transparent)",
        backdropFilter: "blur(10px)",
        borderBottom: "1px solid var(--rule)",
      }}
    >
      <div className="wrap" style={{ display: "flex", alignItems: "center", justifyContent: "space-between", paddingBlock: 14, gap: 16 }}>
        <span style={{ fontFamily: "var(--font-display)", fontWeight: 700, fontSize: 19 }}>Setu</span>

        <ul className="nav-tabs" style={{ display: "flex", gap: 4, listStyle: "none", margin: 0, padding: 0 }}>
          {TABS.map((tab) => (
            <li key={tab.id}>
              <a
                href={`#${tab.id}`}
                style={{
                  display: "block",
                  padding: "8px 14px",
                  fontSize: 13,
                  fontWeight: 600,
                  borderRadius: 3,
                  textDecoration: "none",
                  color: active === tab.id ? "var(--ink)" : "var(--ink-soft)",
                  background: active === tab.id ? "var(--bg-elev)" : "transparent",
                }}
              >
                {tab.label}
              </a>
            </li>
          ))}
        </ul>

        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <Link to="/login" className="btn btn-ghost nav-cta" style={{ padding: "9px 18px", fontSize: 13 }}>
            Sign in
          </Link>
          <a href="#access" className="btn btn-primary nav-cta" style={{ padding: "9px 18px", fontSize: 13 }}>
            Request access
          </a>
          <button
            type="button"
            className="nav-mobile-toggle"
            aria-label={menuOpen ? "Close menu" : "Open menu"}
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((v) => !v)}
            style={{
              display: "none",
              width: 40,
              height: 40,
              alignItems: "center",
              justifyContent: "center",
              background: "transparent",
              border: "1px solid var(--rule)",
              borderRadius: 3,
            }}
          >
            <span aria-hidden="true" style={{ position: "relative", width: 16, height: 12 }}>
              <span style={navBarStyle(menuOpen ? "translateY(5px) rotate(45deg)" : "translateY(0) rotate(0)")} />
              <span style={{ ...navBarStyle("translateY(5px)"), opacity: menuOpen ? 0 : 1 }} />
              <span style={navBarStyle(menuOpen ? "translateY(5px) rotate(-45deg)" : "translateY(10px) rotate(0)")} />
            </span>
          </button>
        </div>
      </div>

      {menuOpen && (
        <div className="nav-mobile-menu" style={{ borderTop: "1px solid var(--rule)" }}>
          <ul style={{ listStyle: "none", margin: 0, padding: "8px 24px 16px", display: "flex", flexDirection: "column" }}>
            {TABS.map((tab) => (
              <li key={tab.id}>
                <a
                  href={`#${tab.id}`}
                  onClick={() => setMenuOpen(false)}
                  style={{
                    display: "block",
                    padding: "12px 4px",
                    fontSize: 15,
                    fontWeight: 600,
                    textDecoration: "none",
                    color: active === tab.id ? "var(--ink)" : "var(--ink-soft)",
                    borderBottom: "1px solid var(--rule)",
                  }}
                >
                  {tab.label}
                </a>
              </li>
            ))}
          </ul>
          <div style={{ padding: "0 24px 20px", display: "flex", flexDirection: "column", gap: 10 }}>
            <Link to="/login" onClick={() => setMenuOpen(false)} className="btn btn-ghost" style={{ width: "100%", justifyContent: "center" }}>
              Sign in
            </Link>
            <a href="#access" onClick={() => setMenuOpen(false)} className="btn btn-primary" style={{ width: "100%", justifyContent: "center" }}>
              Request access
            </a>
          </div>
        </div>
      )}
    </nav>
  );
}

function navBarStyle(transform: string): CSSProperties {
  return {
    position: "absolute",
    left: 0,
    top: 0,
    width: 16,
    height: 2,
    background: "var(--ink)",
    transition: "transform 0.2s ease, opacity 0.2s ease",
    transform,
  };
}
