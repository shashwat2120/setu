import { useEffect, useRef } from "react";
import { Link } from "react-router-dom";
import { gsap, prefersReducedMotion } from "../lib/gsap";

export default function Finale() {
  const stampRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const stamp = stampRef.current;
    if (!stamp) return;

    if (prefersReducedMotion()) {
      gsap.set(stamp, { opacity: 1, scale: 1, rotate: -8 });
      return;
    }

    gsap.set(stamp, { opacity: 0, scale: 1.6, rotate: -8 });
    const tween = gsap.to(stamp, {
      opacity: 1,
      scale: 1,
      rotate: -8,
      duration: 0.5,
      ease: "back.out(2.2)",
      paused: true,
      onComplete: () => stamp.classList.add("stamp-idle"),
      scrollTrigger: { trigger: stamp, start: "top 85%", onEnter: () => tween.play() },
    });
    return () => {
      tween.scrollTrigger?.kill();
      tween.kill();
    };
  }, []);

  return (
    <section id="access" style={{ background: "#141B2C", color: "#F3F1E7", textAlign: "center", position: "relative", overflow: "hidden", paddingBlock: 88 }}>
      <div className="wrap">
        <div
          ref={stampRef}
          className="stamp"
          style={{
            width: 132,
            height: 132,
            border: "3px solid #D4A73D",
            borderRadius: "50%",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            margin: "0 auto 28px",
            position: "relative",
          }}
        >
          <span style={{ fontFamily: "var(--font-mono)", fontSize: 12, letterSpacing: "0.1em", color: "#D4A73D", textAlign: "center", lineHeight: 1.5 }}>
            VERIFIED
            <br />
            BY DESIGN
          </span>
        </div>
        <h2 style={{ fontSize: "clamp(30px, 4.4vw, 48px)", color: "#FBF9F1", maxWidth: 640, margin: "0 auto" }}>
          The bridge is ready to build on.
        </h2>
        <p style={{ color: "#B9B6AA", margin: "16px auto 0", maxWidth: 420 }}>
          Identity, tenancy, enrollment, attendance, grading and risk — one spine, evaluated the same way for every
          role.
        </p>
        <div style={{ display: "flex", gap: 12, justifyContent: "center", marginTop: 32, flexWrap: "wrap" }}>
          <Link to="/login" className="btn btn-primary">Sign in</Link>
          <a href="#spine" className="btn btn-ghost" style={{ borderColor: "rgba(255,255,255,0.25)", color: "#F3F1E7" }}>
            Read the non-negotiables
          </a>
        </div>
      </div>
    </section>
  );
}
