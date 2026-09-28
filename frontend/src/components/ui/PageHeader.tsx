export function PageHeader({ eyebrow, title, subtitle }: { eyebrow?: string; title: string; subtitle?: string }) {
  return (
    <div style={{ marginBottom: 24 }}>
      {eyebrow && <div className="eyebrow" style={{ marginBottom: 8 }}>{eyebrow}</div>}
      <h1 style={{ fontSize: 26, letterSpacing: '-0.01em' }}>{title}</h1>
      {subtitle && <p style={{ color: 'var(--ink-soft)', marginTop: 6, fontSize: 15 }}>{subtitle}</p>}
      <div style={{ width: 40, height: 2, background: 'var(--accent)', marginTop: 14 }} />
    </div>
  );
}
