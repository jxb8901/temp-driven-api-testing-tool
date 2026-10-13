import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { ResourceSummary } from './types';

const ROW_HEIGHT = 40;
const OVERSCAN = 8;

interface ResourceTreeProps {
  items: ResourceSummary[];
  selectedKey: string;
  loading: boolean;
  nextCursor?: string | null;
  onSelect: (resource: ResourceSummary) => void;
  onLoadMore: () => void;
}

export function resourceKey(resource: Pick<ResourceSummary, 'type' | 'resourceId'>): string {
  return `${resource.type}:${resource.resourceId}`;
}

export function ResourceTree({ items, selectedKey, loading, nextCursor, onSelect, onLoadMore }: ResourceTreeProps) {
  const viewport = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [activeIndex, setActiveIndex] = useState(0);
  const visibleCount = 12;
  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
  const end = Math.min(items.length, start + visibleCount + OVERSCAN * 2);
  const visible = items.slice(start, end);
  const selectedIndex = useMemo(() => items.findIndex(item => resourceKey(item) === selectedKey), [items, selectedKey]);

  useEffect(() => {
    if (!items.length) setActiveIndex(0);
    else setActiveIndex(current => Math.min(current, items.length - 1));
  }, [items.length]);

  const focusIndex = (next: number) => {
    if (!items.length) return;
    const bounded = Math.max(0, Math.min(items.length - 1, next));
    setActiveIndex(bounded);
    const element = viewport.current;
    if (element) {
      const top = bounded * ROW_HEIGHT;
      if (top < element.scrollTop) element.scrollTop = top;
      else if (top + ROW_HEIGHT > element.scrollTop + element.clientHeight) element.scrollTop = top + ROW_HEIGHT - element.clientHeight;
    }
    requestAnimationFrame(() => viewport.current?.querySelector<HTMLButtonElement>(`[data-index="${bounded}"]`)?.focus());
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowDown') { event.preventDefault(); focusIndex(activeIndex + 1); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); focusIndex(activeIndex - 1); }
    else if (event.key === 'Home') { event.preventDefault(); focusIndex(0); }
    else if (event.key === 'End') { event.preventDefault(); focusIndex(items.length - 1); }
    else if (event.key === 'Enter' || event.key === ' ') {
      const item = items[activeIndex];
      if (item) { event.preventDefault(); onSelect(item); }
    }
  };

  return <section className="att-explorer-tree" aria-label="Package resources">
    <div className="att-explorer-tree-scroll" ref={viewport} onScroll={event => setScrollTop(event.currentTarget.scrollTop)}
      onKeyDown={onKeyDown} role="tree" aria-label="Resources" aria-busy={loading}>
      <div aria-hidden="true" style={{ height: start * ROW_HEIGHT }} />
      {visible.map((item, offset) => {
        const index = start + offset;
        const key = resourceKey(item);
        return <button key={key} type="button" role="treeitem" data-index={index}
          className="att-explorer-tree-row" style={{ height: ROW_HEIGHT }}
          aria-level={1} aria-posinset={index + 1} aria-setsize={items.length}
          aria-selected={key === selectedKey} tabIndex={activeIndex === index ? 0 : -1}
          onFocus={() => setActiveIndex(index)} onClick={() => { setActiveIndex(index); onSelect(item); }}>
          <span className="att-explorer-tree-kind">{item.type}</span>
          <span className="att-explorer-tree-name">{item.name || item.logicalId || item.resourceId}</span>
          {item.state === 'invalid' && <span className="att-status-badge" data-state="error">Invalid</span>}
        </button>;
      })}
      {end < items.length && <div aria-hidden="true" style={{ height: (items.length - end) * ROW_HEIGHT }} />}
      {!items.length && !loading && <p className="att-explorer-empty">No resources match this filter.</p>}
    </div>
    <div className="att-explorer-tree-footer">
      <span role="status">{selectedIndex >= 0 ? `${items.length} resources; selected ${selectedIndex + 1}` : `${items.length} resources loaded`}</span>
      {nextCursor && <button type="button" onClick={onLoadMore} disabled={loading}>{loading ? 'Loading…' : 'Load more'}</button>}
    </div>
  </section>;
}
