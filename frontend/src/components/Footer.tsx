export default function Footer() {
  return (
    <footer style={{ paddingBlock: "36px calc(36px + env(safe-area-inset-bottom, 0px))", borderTop: "1px solid var(--rule)" }}>
      <div className="wrap" style={{ display: "flex", justifyContent: "space-between", flexWrap: "wrap", gap: 16, fontSize: 13, color: "var(--ink-soft)" }}>
        <span>Setu · college operating system</span>
        <span className="mono">SIS · LMS · risk engine</span>
      </div>
    </footer>
  );
}
