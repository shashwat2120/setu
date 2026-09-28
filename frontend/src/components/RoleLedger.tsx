import { useRef, useState } from "react";
import { gsap } from "../lib/gsap";
import Reveal from "./Reveal";

const ROLES = [
  { id: "admin", idx: "01", title: "College admin", desc: "Registrar operations — enrollment, sections, capacity, holds.", tag: "Built", rot: -2.2 },
  { id: "faculty", idx: "02", title: "Faculty", desc: "Live QR attendance over WebSocket, plus per-student risk detail.", tag: "Built", rot: 1.6 },
  { id: "student", idx: "03", title: "Student", desc: "Attendance, grades, career, finance and an AI-explained risk overview.", tag: "Built", rot: -1.2 },
  { id: "guardian", idx: "04", title: "Guardian", desc: "A reduced, read-only view — access is an explicit, revocable grant.", tag: "Built", rot: 2 },
  { id: "super-admin", idx: "05", title: "Super admin", desc: "Platform-wide: colleges, the audit trail, step-up-gated role grants.", tag: "Built", rot: -1.6 },
  { id: "placement", idx: "06", title: "Placement officer", desc: "Job listings, skill requirements, applications, placement records.", tag: "Built", rot: 1.2 },
];

function RoleCard({ role, i }: { role: (typeof ROLES)[number]; i: number }) {
  const cardRef = useRef<HTMLDivElement>(null);
  const [rotFrom] = useState(role.rot * 3);

  return (
    <Reveal
      as="div"
      rotateFrom={rotFrom}
      delay={(i % 4) * 0.05}
      style={{ transformOrigin: "50% 100%" }}
    >
      <div
        ref={cardRef}
        id={role.id}
        onMouseMove={(e) => {
          const r = cardRef.current!.getBoundingClientRect();
          const x = (e.clientX - r.left) / r.width - 0.5;
          const y = (e.clientY - r.top) / r.height - 0.5;
          gsap.to(cardRef.current, { rotateY: x * 6, rotateX: -y * 6, transformPerspective: 600, duration: 0.3, ease: "power2.out" });
        }}
        onMouseLeave={() => gsap.to(cardRef.current, { rotateX: 0, rotateY: 0, duration: 0.4, ease: "power2.out" })}
        style={{
          background: "var(--bg-elev)",
          border: "1px solid var(--rule)",
          borderRadius: 2,
          padding: "22px 20px 24px",
          position: "relative",
          boxShadow: "0 1px 0 var(--rule)",
        }}
      >
        <div style={{ fontFamily: "var(--font-mono)", fontSize: 12, color: "var(--accent)" }}>{role.idx}</div>
        <h3 style={{ fontSize: 18, marginTop: 10 }}>{role.title}</h3>
        <p style={{ fontSize: 13.5, color: "var(--ink-soft)", marginTop: 8 }}>{role.desc}</p>
        <span
          style={{
            display: "inline-block",
            marginTop: 14,
            fontFamily: "var(--font-mono)",
            fontSize: 10.5,
            letterSpacing: "0.06em",
            textTransform: "uppercase",
            padding: "3px 8px",
            border: "1px solid var(--rule)",
            borderRadius: 2,
            color: "var(--ink-soft)",
          }}
        >
          {role.tag}
        </span>
      </div>
    </Reveal>
  );
}

export default function RoleLedger() {
  return (
    <section className="wrap">
      <div style={{ maxWidth: 640, marginBottom: 44 }}>
        <div className="eyebrow">The ledger — 02</div>
        <h2 style={{ fontSize: "clamp(26px, 3.4vw, 38px)", marginTop: 10 }}>Six roles. One system of record.</h2>
      </div>
      <div className="role-grid">
        {ROLES.map((role, i) => (
          <RoleCard key={role.id} role={role} i={i} />
        ))}
      </div>
    </section>
  );
}
