import { useEffect, useRef } from "react";
import { gsap, prefersReducedMotion } from "../lib/gsap";

const CELLS = [
  { count: 6, label: "roles evaluated per request" },
  { count: 10, label: "non-negotiables enforced" },
  { count: 1, label: "source of truth — Postgres" },
  { count: 0, label: "academic facts invented by AI" },
];

function TallyCell({ count, label }: { count: number; label: string }) {
  const numRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = numRef.current;
    if (!el) return;

    if (prefersReducedMotion()) {
      el.textContent = String(count);
      return;
    }

    el.textContent = "0";
    const obj = { v: 0 };
    const tween = gsap.to(obj, {
      v: count,
      duration: 1.1,
      ease: "power1.out",
      paused: true,
      onUpdate: () => {
        el.textContent = String(Math.round(obj.v));
      },
      scrollTrigger: { trigger: el, start: "top 90%", onEnter: () => tween.play() },
    });
    return () => {
      tween.scrollTrigger?.kill();
      tween.kill();
    };
  }, [count]);

  return (
    <div style={{ background: "var(--bg-elev)", padding: "28px 20px" }}>
      <div ref={numRef} style={{ fontFamily: "var(--font-mono)", fontSize: "clamp(32px, 4vw, 44px)", fontWeight: 600 }}>
        0
      </div>
      <div style={{ fontSize: 12.5, color: "var(--ink-soft)", marginTop: 6 }}>{label}</div>
    </div>
  );
}

export default function Tally() {
  return (
    <section>
      <div className="wrap">
        <div className="tally-grid" style={{ background: "var(--rule)", border: "1px solid var(--rule)" }}>
          {CELLS.map((cell) => (
            <TallyCell key={cell.label} {...cell} />
          ))}
        </div>
      </div>
    </section>
  );
}
