import { createElement, useEffect, useRef, type CSSProperties, type ElementType, type ReactNode } from "react";
import { gsap, prefersReducedMotion } from "../lib/gsap";

interface RevealProps {
  children: ReactNode;
  as?: ElementType;
  className?: string;
  style?: CSSProperties;
  delay?: number;
  rotateFrom?: number;
}

/** Fades an element up into view the first time it scrolls within range. Shared across every section. */
export default function Reveal({ children, as = "div", className, style, delay = 0, rotateFrom }: RevealProps) {
  const ref = useRef<HTMLElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const reduced = prefersReducedMotion();

    if (reduced) {
      gsap.set(el, { opacity: 1, y: 0, rotate: 0 });
      return;
    }

    gsap.set(el, { opacity: 0, y: 22, rotate: rotateFrom ?? 0 });
    // No paused:true + manual onEnter-play here — that requires referencing the tween's own `const` binding
    // from inside a callback ScrollTrigger can invoke SYNCHRONOUSLY during creation (when the element is
    // already within the trigger zone at mount, e.g. a short page or a tall viewport), which reads the
    // binding before its assignment completes (a real crash found live, not a hypothetical). Attaching
    // scrollTrigger directly to the tween lets GSAP itself own the play timing — its default toggleActions
    // ("play none none none") already gives the same one-shot-on-enter behavior without the race.
    const trigger = gsap.to(el, {
      opacity: 1,
      y: 0,
      rotate: 0,
      duration: rotateFrom ? 0.7 : 0.55,
      ease: rotateFrom ? "back.out(1.8)" : "power2.out",
      delay,
      scrollTrigger: {
        trigger: el,
        start: "top 88%",
      },
    });
    return () => {
      trigger.scrollTrigger?.kill();
      trigger.kill();
    };
  }, [delay, rotateFrom]);

  return createElement(as, { ref, className, style }, children);
}
