export function SearchInput({
  value, onChange, placeholder, label,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  label: string;
}) {
  return (
    <div style={{ marginBottom: 16, maxWidth: 320 }}>
      <label htmlFor="search" style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 6 }}>
        {label}
      </label>
      <input
        id="search"
        type="search"
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        style={{
          width: '100%',
          fontFamily: 'var(--font-body)',
          fontSize: 14,
          padding: '9px 12px',
          border: '1px solid var(--rule)',
          borderRadius: 4,
          background: 'var(--bg-elev)',
          color: 'var(--ink)',
        }}
      />
    </div>
  );
}
