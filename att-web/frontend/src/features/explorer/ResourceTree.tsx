import type { ResourceSortField, ResourceSummary } from './types';
import type { RefObject, UIEvent } from 'react';
import { useState, type ReactElement } from 'react';

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
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({});
  const grouped = new Map<string, ResourceSummary[]>();
  for (const item of items) {
    if (item.type !== 'tool' || typeof item.provenance?.toolGroupManifest !== 'string') continue;
    const groupId = String(item.provenance?.toolGroupId || '');
    if (!groupId) continue;
    const members = grouped.get(groupId) || [];
    members.push(item);
    grouped.set(groupId, members);
  }
  const renderedGroups = new Set<string>();
  const renderResource = (item: ResourceSummary, nested = false) => <tr key={resourceKey(item)} aria-selected={resourceKey(item) === selectedKey} className={nested ? 'att-explorer-group-member' : undefined}>
    <th scope="row"><button type="button" className="att-explorer-resource-name" aria-pressed={resourceKey(item) === selectedKey}
      onClick={() => onSelect(item)}>{item.name || item.logicalId || item.resourceId}</button></th>
    <td>{item.type}</td><td>{item.logicalId || '—'}</td><td>{item.state === 'invalid' ? <span className="att-status-badge" data-state="error">Invalid</span> : item.state || 'Ready'}</td>
    <td><div className="att-explorer-row-actions">
      <button className="button button-quiet" type="button" onClick={() => onSelect(item)}>View</button>
      {onRun && item.state !== 'invalid' && <button className="button button-quiet" type="button" onClick={() => onRun(item)}>Run</button>}
      {onQuickLoad && item.state !== 'invalid' && ['template','flow','tool'].includes(item.type) && <button className="button button-quiet" type="button" onClick={() => onQuickLoad(item)}>Quick LOAD</button>}
    </div></td>
  </tr>;

  const rows: ReactElement[] = [];
  for (const item of items) {
    const groupId = item.type === 'tool' && typeof item.provenance?.toolGroupManifest === 'string'
      ? String(item.provenance?.toolGroupId || '') : '';
    const members = groupId ? grouped.get(groupId) : undefined;
    if (!members) {
      rows.push(renderResource(item));
      continue;
    }
    if (renderedGroups.has(groupId)) continue;
    renderedGroups.add(groupId);
    const expanded = expandedGroups[groupId] !== false;
    rows.push(<tr key={`tool-group:${groupId}`} className="att-explorer-tool-group">
      <th scope="row"><button type="button" className="att-explorer-resource-name" aria-expanded={expanded}
        onClick={() => setExpandedGroups(current => ({ ...current, [groupId]: !expanded }))}>
        {expanded ? '▾' : '▸'} {groupId}
      </button></th>
      <td>Tool Group</td><td>{groupId}</td><td>{members.length} {members.length === 1 ? 'Tool shown' : 'Tools shown'}</td><td />
    </tr>);
    if (expanded) members.forEach(member => rows.push(renderResource(member, true)));
  }
  const sortLabel = (key: ResourceSortField, label: string) => <button type="button" className="att-explorer-sort"
    aria-label={'Sort all matching resources by ' + label} onClick={() => onSortChange(key)}>
    {label}{sortKey === key ? (direction === 'desc' ? ' ↓' : ' ↑') : ''}
  </button>;

  return <section className="att-explorer-tree" aria-label="Package resources">
    <p className="att-explorer-table-hint">Column sorting applies to matching resources before pagination; grouped Tools are then shown inside their Group folders.</p>
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
          {rows}
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
