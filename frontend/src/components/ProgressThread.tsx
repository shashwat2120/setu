import { useEffect, useRef } from "react";
import { gsap } from "../lib/gsap";

export default function ProgressThread() {
  const fillRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!fillRef.current) return;
    const tween = gsap.to(fillRef.current, {
      height: "100%",
      ease: "none",
      scrollTrigger: {
        trigger: document.body,
        start: "top top",
        end: "bottom bottom",
        scrub: 0.3,
      },
    });
    return () => {
      tween.scrollTrigger?.kill();
      tween.kill();
    };
  }, []);

  return (
    <div
      aria-hidden="true"
      style={{
        position: "fixed",
        left: 0,
        top: 0,
        bottom: 0,
        width: 3,
        background: "var(--rule)",
        zIndex: 60,
      }}
    >
      <div
        ref={fillRef}
        style={{ position: "absolute", left: 0, top: 0, width: "100%", height: "0%", background: "var(--accent)" }}
      />
    </div>
  );
}
