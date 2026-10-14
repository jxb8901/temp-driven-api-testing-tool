import { useMemo, useState } from 'react';
import type { ResourceSummary } from './types';

interface ResourceTreeProps {
  items: ResourceSummary[];
  selectedKey: string;
  loading: boolean;
  nextCursor?: string | null;
  onSelect: (resource: ResourceSummary) => void;
  onLoadMore: () => void;
}

export function resourceKey(resource: Pick<ResourceSummary, 'type' | 'resourceId'>): string {
  return resource.type + ':' + resource.resourceId;
}

type SortKey = 'name' | 'type' | 'logicalId' | 'state';

export function ResourceTree({ items, selectedKey, loading, nextCursor, onSelect, onLoadMore }: ResourceTreeProps) {
  const [sortKey, setSortKey] = useState<SortKey>('name');
  const [descending, setDescending] = useState(false);
  const sorted = useMemo(() => [...items].sort((left, right) => {
    const value = (item: ResourceSummary) => String(sortKey === 'name' ? item.name || item.logicalId || item.resourceId
      : sortKey === 'logicalId' ? item.logicalId || '' : sortKey === 'state' ? item.state || 'ready' : item.type).toLocaleLowerCase();
    const compared = value(left).localeCompare(value(right), undefined, { numeric: true, sensitivity: 'base' });
    return descending ? -compared : compared;
  }), [items, sortKey, descending]);
  const changeSort = (next: SortKey) => {
    if (next === sortKey) setDescending(value => !value);
    else { setSortKey(next); setDescending(false); }
  };
  const sortLabel = (key: SortKey, label: string) => <button type="button" className="att-explorer-sort"
    aria-label={'Sort loaded resources by ' + label} onClick={() => changeSort(key)}>
    {label}{sortKey === key ? (descending ? ' ↓' : ' ↑') : ''}
  </button>;

  return <section className="att-explorer-tree" aria-label="Package resources">
    <p className="att-explorer-table-hint">Select a column heading to sort the currently loaded rows.</p>
    <div className="att-explorer-table-scroll">
      <table className="att-explorer-table">
        <thead><tr>
          <th scope="col" aria-sort={sortKey === 'name' ? descending ? 'descending' : 'ascending' : 'none'}>{sortLabel('name', 'Name')}</th>
          <th scope="col" aria-sort={sortKey === 'type' ? descending ? 'descending' : 'ascending' : 'none'}>{sortLabel('type', 'Type')}</th>
          <th scope="col" aria-sort={sortKey === 'logicalId' ? descending ? 'descending' : 'ascending' : 'none'}>{sortLabel('logicalId', 'Logical ID')}</th>
          <th scope="col" aria-sort={sortKey === 'state' ? descending ? 'descending' : 'ascending' : 'none'}>{sortLabel('state', 'Status')}</th>
        </tr></thead>
        <tbody aria-busy={loading}>
          {sorted.map(item => <tr key={resourceKey(item)} aria-selected={resourceKey(item) === selectedKey}>
            <th scope="row"><button type="button" className="att-explorer-resource-name" aria-pressed={resourceKey(item) === selectedKey}
              onClick={() => onSelect(item)}>{item.name || item.logicalId || item.resourceId}</button></th>
            <td>{item.type}</td><td>{item.logicalId || '—'}</td><td>{item.state === 'invalid' ? <span className="att-status-badge" data-state="error">Invalid</span> : item.state || 'Ready'}</td>
          </tr>)}
        </tbody>
      </table>
      {!items.length && !loading && <p className="att-explorer-empty">No resources match this category and search.</p>}
    </div>
    <div className="att-explorer-tree-footer">
      <span role="status">{loading ? 'Loading resources…' : items.length + ' loaded resource' + (items.length === 1 ? '' : 's')}</span>
      {nextCursor && <button type="button" onClick={onLoadMore} disabled={loading}>{loading ? 'Loading…' : 'Load more'}</button>}
    </div>
  </section>;
}
