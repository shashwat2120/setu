interface PaginationProps {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}

export function Pagination({ page, totalPages, onChange }: PaginationProps) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="Pagination" style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 16 }}>
      <button
        type="button"
        className="btn btn-ghost"
        style={{ padding: '8px 14px', fontSize: 13 }}
        disabled={page <= 0}
        onClick={() => onChange(page - 1)}
      >
        Previous
      </button>
      <span className="mono" style={{ fontSize: 13, color: 'var(--ink-soft)' }} aria-live="polite">
        Page {page + 1} of {totalPages}
      </span>
      <button
        type="button"
        className="btn btn-ghost"
        style={{ padding: '8px 14px', fontSize: 13 }}
        disabled={page >= totalPages - 1}
        onClick={() => onChange(page + 1)}
      >
        Next
      </button>
    </nav>
  );
}
