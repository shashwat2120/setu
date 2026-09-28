import { useEffect, useRef } from "react";
import { gsap, ScrollTrigger, prefersReducedMotion } from "../lib/gsap";

const STAGES = [
  { n: "i", label: "Identity & tenancy" },
  { n: "ii", label: "Course & enrollment" },
  { n: "iii", label: "QR attendance" },
  { n: "iv", label: "Assessment & grade" },
  { n: "v", label: "Risk engine" },
  { n: "vi", label: "AI explains risk" },
];

export default function Spine() {
  const pinRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const nodeRefs = useRef<(HTMLDivElement | null)[]>([]);

  useEffect(() => {
    const reduced = prefersReducedMotion();
    const pin = pinRef.current;
    const track = trackRef.current;
    if (!pin || !track) return;

    if (reduced) {
      nodeRefs.current.forEach((n) => n && n.classList.add("is-active"));
      return;
    }

    const maxX = Math.max(0, track.scrollWidth - track.parentElement!.offsetWidth);
    const scrub = gsap.to(track, {
      x: -maxX,
      ease: "none",
      scrollTrigger: { trigger: pin, start: "top top", end: "bottom bottom", scrub: 0.5 },
    });

    const triggers = nodeRefs.current.map((node) => {
      if (!node) return null;
      return ScrollTrigger.create({
        trigger: pin,
        start: "top top",
        end: "bottom bottom",
        onUpdate: () => {
          const r = node.getBoundingClientRect();
          const vw = window.innerWidth;
          node.classList.toggle("is-active", r.left < vw * 0.65 && r.right > vw * 0.15);
        },
      });
    });

    return () => {
      scrub.scrollTrigger?.kill();
      scrub.kill();
      triggers.forEach((t) => t?.kill());
    };
  }, []);

  return (
    <section className="wrap" id="spine">
      <div style={{ maxWidth: 640, marginBottom: 44 }}>
        <div className="eyebrow">The spine — 03</div>
        <h2 style={{ fontSize: "clamp(26px, 3.4vw, 38px)", marginTop: 10 }}>What has to work, in order.</h2>
      </div>
      <div ref={pinRef} style={{ position: "relative", overflow: "hidden", height: 900 }}>
        <div style={{ position: "sticky", top: 0 }}>
          <div ref={trackRef} style={{ display: "flex", alignItems: "center", willChange: "transform" }}>
            {STAGES.map((stage, i) => (
              <div key={stage.n} style={{ display: "flex", alignItems: "center" }}>
                <div
                  ref={(el) => {
                    nodeRefs.current[i] = el;
                  }}
                  className="spine-node"
                  style={{
                    flex: "0 0 auto",
                    width: 168,
                    padding: "16px 14px",
                    border: "1px dashed var(--rule)",
                    borderRadius: 2,
                    background: "var(--bg-elev)",
                  }}
                >
                  <div style={{ fontFamily: "var(--font-mono)", fontSize: 11, color: "var(--accent)" }}>{stage.n}</div>
                  <h4 style={{ fontFamily: "var(--font-body)", fontWeight: 600, fontSize: 14, marginTop: 6 }}>{stage.label}</h4>
                </div>
                {i < STAGES.length - 1 && <span style={{ color: "var(--rule)", padding: "0 16px", fontSize: 18 }}>→</span>}
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
