import type { ReactNode } from 'react';
import { Skeleton } from './EmptyState';
import { EmptyState, ErrorState } from './EmptyState';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  /** Shown as the mobile card's field label — defaults to `header`. */
  mobileLabel?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[] | null;
  loading: boolean;
  error: Error | null;
  onRetry?: () => void;
  emptyTitle: string;
  emptyDescription?: string;
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
}

/** The one reusable table every admin/faculty list page uses — loading/error/empty states built in, not re-implemented per page. */
export function DataTable<T>({
  columns, rows, loading, error, onRetry, emptyTitle, emptyDescription, rowKey, onRowClick,
}: DataTableProps<T>) {
  if (loading) {
    return (
      <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {Array.from({ length: 5 }).map((_, i) => (
          <Skeleton key={i} height={20} />
        ))}
      </div>
    );
  }

  if (error) {
    return (
      <div className="card">
        <ErrorState message="Could not load this data right now." onRetry={onRetry} />
      </div>
    );
  }

  if (!rows || rows.length === 0) {
    return (
      <div className="card">
        <EmptyState title={emptyTitle} description={emptyDescription} />
      </div>
    );
  }

  return (
    <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
      <table className="data-table">
        <thead>
          <tr>
            {columns.map((col) => (
              <th key={col.key} scope="col">{col.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              style={onRowClick ? { cursor: 'pointer' } : undefined}
              tabIndex={onRowClick ? 0 : undefined}
              role={onRowClick ? 'button' : undefined}
              onKeyDown={
                onRowClick
                  ? (e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        onRowClick(row);
                      }
                    }
                  : undefined
              }
            >
              {columns.map((col) => (
                <td key={col.key} data-label={col.mobileLabel ?? col.header}>
                  {col.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
