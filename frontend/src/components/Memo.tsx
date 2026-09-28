import Reveal from "./Reveal";

export default function Memo() {
  return (
    <section className="wrap">
      <div className="memo-grid">
        <div className="eyebrow">Memo — 01</div>
        <div>
          <Reveal as="p" style={{ fontFamily: "var(--font-display)", fontStyle: "italic", fontSize: "clamp(20px, 2.6vw, 28px)", lineHeight: 1.5, maxWidth: 640 }}>
            &ldquo;Setu means bridge. The permission model is built around exactly that — role, tenant, resource,
            action, workflow state and ownership, evaluated on every request.&rdquo;
          </Reveal>
          <p style={{ marginTop: 20, fontSize: 13, color: "var(--ink-soft)" }}>— from the Setu non-negotiables</p>
        </div>
      </div>
    </section>
  );
}
