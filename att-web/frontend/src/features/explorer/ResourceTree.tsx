import type { ResourceSortField, ResourceSummary } from './types';
import type { RefObject, UIEvent } from 'react';

interface ResourceTreeProps {
  items: ResourceSummary[];
  selectedKey: string;
  loading: boolean;
  nextCursor?: string | null;
  sortKey: ResourceSortField;
  direction: 'asc' | 'desc';
  onSortChange: (field: ResourceSortField) => void;
  onSelect: (resource: ResourceSummary) => void;
  onRun?: (resource: ResourceSummary) => void;
  onQuickLoad?: (resource: ResourceSummary) => void;
  onLoadMore: () => void;
  scrollContainerRef?: RefObject<HTMLDivElement | null>;
  onListScroll?: (event: UIEvent<HTMLDivElement>) => void;
}

export function resourceKey(resource: Pick<ResourceSummary, 'type' | 'resourceId'>): string {
  return resource.type + ':' + resource.resourceId;
}

export function ResourceTree({ items, selectedKey, loading, nextCursor, sortKey, direction, onSortChange, onSelect, onRun, onQuickLoad, onLoadMore, scrollContainerRef, onListScroll }: ResourceTreeProps) {
  const sortLabel = (key: ResourceSortField, label: string) => <button type="button" className="att-explorer-sort"
    aria-label={'Sort all matching resources by ' + label} onClick={() => onSortChange(key)}>
    {label}{sortKey === key ? (direction === 'desc' ? ' ↓' : ' ↑') : ''}
  </button>;

  return <section className="att-explorer-tree" aria-label="Package resources">
    <p className="att-explorer-table-hint">Column sorting applies to all matching resources before pagination.</p>
    <div ref={scrollContainerRef} onScroll={onListScroll} className="att-explorer-table-scroll">
      <table className="att-explorer-table att-data-table">
        <thead><tr>
          <th scope="col" aria-sort={sortKey === 'name' ? direction === 'desc' ? 'descending' : 'ascending' : 'none'}>{sortLabel('name', 'Name')}</th>
          <th scope="col" aria-sort={sortKey === 'type' ? direction === 'desc' ? 'descending' : 'ascending' : 'none'}>{sortLabel('type', 'Type')}</th>
          <th scope="col" aria-sort={sortKey === 'logicalId' ? direction === 'desc' ? 'descending' : 'ascending' : 'none'}>{sortLabel('logicalId', 'Logical ID')}</th>
          <th scope="col" aria-sort={sortKey === 'state' ? direction === 'desc' ? 'descending' : 'ascending' : 'none'}>{sortLabel('state', 'Status')}</th>
          <th scope="col">Actions</th>
        </tr></thead>
        <tbody aria-busy={loading}>
          {items.map(item => <tr key={resourceKey(item)} aria-selected={resourceKey(item) === selectedKey}>
            <th scope="row"><button type="button" className="att-explorer-resource-name" aria-pressed={resourceKey(item) === selectedKey}
              onClick={() => onSelect(item)}>{item.name || item.logicalId || item.resourceId}</button></th>
            <td>{item.type}</td><td>{item.logicalId || '—'}</td><td>{item.state === 'invalid' ? <span className="att-status-badge" data-state="error">Invalid</span> : item.state || 'Ready'}</td>
            <td><div className="att-explorer-row-actions">
              <button className="button button-quiet" type="button" onClick={() => onSelect(item)}>View</button>
              {onRun && item.state !== 'invalid' && <button className="button button-quiet" type="button" onClick={() => onRun(item)}>Run</button>}
              {onQuickLoad && item.state !== 'invalid' && ['template','flow','tool'].includes(item.type) && <button className="button button-quiet" type="button" onClick={() => onQuickLoad(item)}>Quick LOAD</button>}
            </div></td>
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
