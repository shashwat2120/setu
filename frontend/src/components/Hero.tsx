import { useEffect, useRef } from "react";
import { gsap, prefersReducedMotion } from "../lib/gsap";

export default function Hero() {
  const mediaRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const headlineRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const reduced = prefersReducedMotion();
    const ctx = gsap.context(() => {
      if (reduced) {
        gsap.set([headlineRef.current, ".hero-fade"], { opacity: 1, y: 0, scale: 1 });
        return;
      }

      gsap.set(headlineRef.current, { opacity: 0, y: 14, scale: 0.94 });
      gsap.set(".hero-fade", { opacity: 0, y: 10 });

      gsap.to(headlineRef.current, { opacity: 1, y: 0, scale: 1, duration: 0.7, ease: "back.out(1.6)", delay: 0.15 });
      gsap.to(".hero-fade", { opacity: 1, y: 0, duration: 0.6, ease: "power2.out", delay: 0.55, stagger: 0.08 });

      gsap.to(imgRef.current, {
        y: "12%",
        ease: "none",
        scrollTrigger: { trigger: mediaRef.current, start: "top top", end: "bottom top", scrub: 0.4 },
      });
      gsap.to(contentRef.current, {
        y: -40,
        ease: "none",
        scrollTrigger: { trigger: mediaRef.current, start: "top top", end: "bottom top", scrub: 0.4 },
      });

      const media = mediaRef.current;
      const onMove = (e: MouseEvent) => {
        if (!media) return;
        const r = media.getBoundingClientRect();
        const x = (e.clientX - r.left) / r.width - 0.5;
        gsap.to(contentRef.current, { x: x * -14, duration: 0.6, ease: "power2.out", overwrite: "auto" });
        gsap.to(imgRef.current, { x: x * -18, duration: 0.8, ease: "power2.out", overwrite: "auto" });
      };
      media?.addEventListener("mousemove", onMove);
      return () => media?.removeEventListener("mousemove", onMove);
    });
    return () => ctx.revert();
  }, []);

  return (
    <header style={{ position: "relative", overflow: "hidden" }}>
      <div ref={mediaRef} style={{ position: "relative", minHeight: 560, display: "flex", alignItems: "flex-end" }}>
        <img
          ref={imgRef}
          src="/images/hero.jpg"
          alt="Students collaborating around a laptop in a college library"
          style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover", objectPosition: "center 30%", transform: "scale(1.18)", willChange: "transform" }}
        />
        <div
          aria-hidden="true"
          style={{
            position: "absolute",
            inset: 0,
            background: "linear-gradient(180deg, rgba(20,27,44,0.15) 0%, rgba(20,27,44,0.55) 55%, rgba(20,27,44,0.92) 100%)",
          }}
        />
        <div ref={contentRef} className="wrap" style={{ position: "relative", color: "#F3F1E7", padding: "64px 24px 56px", width: "100%", willChange: "transform" }}>
          <div className="hero-fade" style={{ fontFamily: "var(--font-mono)", fontSize: 11, letterSpacing: "0.14em", textTransform: "uppercase", opacity: 0.75, marginBottom: 18, display: "flex", gap: 10, flexWrap: "wrap" }}>
            <span>Setu &middot; College Operating System</span>
            <span>&middot;</span>
            <span>Vol. 01</span>
          </div>
          <h1 ref={headlineRef} style={{ fontSize: "clamp(36px, 6vw, 68px)", lineHeight: 0.98, maxWidth: 820, color: "#FBF9F1" }}>
            Every record,
            <br />
            <span style={{ color: "var(--accent)" }}>one bridge.</span>
          </h1>
          <p className="hero-fade" style={{ fontSize: 17, color: "#D8D4C4", maxWidth: 480, marginTop: 20 }}>
            Setu connects students, faculty, guardians and administration through one deterministic system of
            record — nothing is guessed, and every number can be traced back to its source.
          </p>
          <div className="hero-fade" style={{ display: "flex", gap: 12, marginTop: 32, flexWrap: "wrap" }}>
            <a href="#access" className="btn btn-primary">Request access</a>
            <a href="#spine" className="btn btn-ghost" style={{ borderColor: "rgba(255,255,255,0.35)", color: "#F3F1E7" }}>
              See the spine ↓
            </a>
          </div>
        </div>
      </div>
    </header>
  );
}
