import { useEffect, useRef } from "react";
import { gsap, prefersReducedMotion } from "../lib/gsap";

interface FigureProps {
  src: string;
  alt: string;
  caption: string;
  aspect: string;
}

/** A captioned photo whose label wipes in once the image scrolls into view. Reused wherever a photo appears. */
export default function Figure({ src, alt, caption, aspect }: FigureProps) {
  const figureRef = useRef<HTMLDivElement>(null);
  const capRef = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    const fig = figureRef.current;
    const cap = capRef.current;
    if (!fig || !cap) return;

    if (prefersReducedMotion()) {
      gsap.set(cap, { clipPath: "inset(0 0% 0 0)" });
      return;
    }

    gsap.set(cap, { clipPath: "inset(0 100% 0 0)" });
    const tween = gsap.to(cap, {
      clipPath: "inset(0 0% 0 0)",
      duration: 0.9,
      ease: "power2.out",
      delay: 0.25,
      paused: true,
      scrollTrigger: { trigger: fig, start: "top 75%", onEnter: () => tween.play() },
    });
    return () => {
      tween.scrollTrigger?.kill();
      tween.kill();
    };
  }, []);

  return (
    <div ref={figureRef}>
      <img src={src} alt={alt} style={{ borderRadius: 2, width: "100%", height: "100%", objectFit: "cover", aspectRatio: aspect }} />
      <p ref={capRef} style={{ fontFamily: "var(--font-mono)", fontSize: 11, color: "var(--ink-soft)", marginTop: 8 }}>
        {caption}
      </p>
    </div>
  );
}
