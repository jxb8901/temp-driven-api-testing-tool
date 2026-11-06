import { useEffect, useMemo, useState } from 'react';
import type { ResourceReference } from './types';

const VALUE_PREVIEW_CHARACTERS = 800;
const MAX_STRUCTURED_CHARACTERS = 100_000;
const INITIAL_ACTIONS = 200;
const MORE_ACTIONS = 200;

const COMMON_FIELDS = new Set(['type', 'description', 'runWhen', 'onFailure', 'timeoutMs', 'retry', 'evidence']);
const TYPE_FIELDS: Record<string, ReadonlySet<string>> = {
  tool: new Set(['call']),
  assert: new Set(['assert', 'expected', 'actual']),
  log: new Set(['message', 'value', 'format']),
  assign: new Set(['name', 'expression', 'value']),
  flow: new Set(['use']),
};

interface ActionEntry {
  id: string;
  value: unknown;
  disabled: boolean;
}

interface ActionInspectorProps {
  definition: unknown;
  actionOrder?: string[];
  resourceType: 'template' | 'flow';
  resourceId: string;
  references?: ResourceReference[];
  onOpenFlow: (resourceId: string) => void;
}

function objectRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function boundedText(value: string): string {
  const suffix = `\n\n[Value truncated at ${MAX_STRUCTURED_CHARACTERS.toLocaleString()} characters.]`;
  return value.length > MAX_STRUCTURED_CHARACTERS
    ? `${value.slice(0, MAX_STRUCTURED_CHARACTERS - suffix.length)}${suffix}`
    : value;
}

function safeStringify(value: unknown): string {
  let rendered = '';
  try { rendered = JSON.stringify(value, null, 2) ?? String(value); }
  catch { return '[This value cannot be displayed as structured data.]'; }
  return boundedText(rendered);
}

function displayValue(value: unknown): string {
  return typeof value === 'string' ? boundedText(value) : safeStringify(value);
}

function clippedLabel(value: string): string {
  return value.length > 120 ? `${value.slice(0, 117)}…` : value;
}

function actionEntries(definition: unknown, actionOrder?: string[]): ActionEntry[] | null {
  const root = objectRecord(definition);
  const actions = root && objectRecord(root.actions);
  if (!actions) return null;
  const keys: string[] = [];
  const seen = new Set<string>();
  if (Array.isArray(actionOrder)) {
    for (const key of actionOrder) {
      if (typeof key === 'string' && Object.prototype.hasOwnProperty.call(actions, key) && !seen.has(key)) {
        keys.push(key);
        seen.add(key);
      }
    }
  }
  for (const key of Object.keys(actions)) if (!seen.has(key)) keys.push(key);
  return keys.map(id => ({ id, value: actions[id], disabled: id.startsWith('x-') }));
}

function ValueView({ value, label }: { value: unknown; label: string }) {
  const rendered = displayValue(value);
  if (rendered.length <= VALUE_PREVIEW_CHARACTERS) return <pre className="att-action-value">{rendered}</pre>;
  const truncated = rendered.includes('[Value truncated at ');
  return <details className="att-action-value-details">
    <summary>{truncated ? 'Expand safe preview' : 'Show full value'} ({rendered.length.toLocaleString()} characters)</summary>
    <pre className="att-action-value" aria-label={`${label} ${truncated ? 'safe preview' : 'full value'}`}>{rendered}</pre>
  </details>;
}

function flowReference(entry: ActionEntry, use: unknown, references: ResourceReference[] | undefined) {
  if (typeof use !== 'string' || !use.trim()) return { state: 'invalid' as const };
  const matching = (references || []).filter(reference => reference.type === 'flow'
    && (reference.logicalId === use || reference.resourceId === use)
    && (!reference.callSite?.actionId || reference.callSite.actionId === entry.id));
  const reference = matching.find(candidate => candidate.resolution === 'resolved' && candidate.resourceId)
    || matching[0];
  if (!reference || reference.resolution !== 'resolved' || !reference.resourceId)
    return { state: 'unresolved' as const };
  return { state: 'resolved' as const, resourceId: reference.resourceId, logicalId: reference.logicalId || use };
}

export function ActionInspector({ definition, actionOrder, resourceType, resourceId, references, onOpenFlow }: ActionInspectorProps) {
  const entries = useMemo(() => actionEntries(definition, actionOrder), [definition, actionOrder]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [visibleCount, setVisibleCount] = useState(INITIAL_ACTIONS);

  useEffect(() => { setSelectedIndex(0); setVisibleCount(INITIAL_ACTIONS); }, [definition, actionOrder]);

  if (!entries) return <section className="att-action-inspector" aria-label="Visual action inspector">
    <p role="status">Actions are unavailable because the safe definition does not contain an action map.</p>
    <details><summary>Advanced structured definition</summary><pre className="att-action-value">{safeStringify(definition)}</pre></details>
  </section>;

  if (!entries.length) return <section className="att-action-inspector" aria-label="Visual action inspector">
    <p>No actions are present in this {resourceType === 'flow' ? 'Flow' : 'Template'} definition.</p>
    <details><summary>Advanced structured definition</summary><pre className="att-action-value">{safeStringify(definition)}</pre></details>
  </section>;

  const selected = entries[Math.min(selectedIndex, entries.length - 1)];
  const action = objectRecord(selected.value);
  const type = typeof action?.type === 'string' ? action.type : 'unknown';
  const knownFields = new Set([...COMMON_FIELDS, ...(TYPE_FIELDS[type] || [])]);
  const properties = Object.entries(action || {}).filter(([key]) => knownFields.has(key));
  const extraProperties = Object.entries(action || {}).filter(([key]) => !knownFields.has(key));
  const target = type === 'flow' ? flowReference(selected, action?.use, references) : null;
  const selfTarget = target?.state === 'resolved' && target.resourceId === resourceId;

  return <section className="att-action-inspector" aria-label="Visual action inspector">
    <div className="att-action-inspector-heading">
      <div><h3>Actions</h3><p>Read-only authored order · expressions are not evaluated · no inferred branches · {entries.length.toLocaleString()} {entries.length === 1 ? 'action' : 'actions'}</p></div>
    </div>
    <div className="att-action-inspector-layout">
      <nav className="att-action-list" aria-label="Ordered actions">
        <ol>
          {entries.slice(0, visibleCount).map((entry, index) => {
            const item = objectRecord(entry.value);
            const itemType = typeof item?.type === 'string' ? item.type : 'unknown';
            return <li key={`${entry.id}:${index}`}>
              <button type="button" aria-pressed={index === selectedIndex} aria-current={index === selectedIndex ? 'step' : undefined}
                className={entry.disabled ? 'is-disabled-action' : undefined}
                onClick={() => setSelectedIndex(index)} title={clippedLabel(entry.id)}>
                <span className="att-action-number">{index + 1}</span>
                <span className="att-action-name">{clippedLabel(entry.id)}</span>
                <span className="att-action-type">{itemType}</span>
                {entry.disabled && <span className="att-action-disabled">Disabled</span>}
              </button>
            </li>;
          })}
        </ol>
        {visibleCount < entries.length && <div className="att-action-list-more">
          <p>Showing {Math.min(visibleCount, entries.length).toLocaleString()} of {entries.length.toLocaleString()} actions.</p>
          <button type="button" onClick={() => setVisibleCount(count => Math.min(entries.length, count + MORE_ACTIONS))}>Show next {Math.min(MORE_ACTIONS, entries.length - visibleCount).toLocaleString()} actions</button>
        </div>}
      </nav>

      <article className="att-action-properties" aria-label={`Properties for action ${selected.id}`}>
        <header><p>Action {selectedIndex + 1} of {entries.length}</p><h3>{selected.id}</h3>
          <span className="att-action-type-badge">{type}</span>
          {selected.disabled && <span className="att-action-disabled">Disabled extension entry</span>}
        </header>
        {!action && <p role="status">This action entry is not an object. Its authored value remains available below.</p>}
        {properties.length > 0 && <dl className="att-action-property-list">
          {properties.filter(([key]) => key !== 'type').map(([key, value]) => <div key={key}>
            <dt>{key}</dt><dd><ValueView value={value} label={key} />
              {key === 'runWhen' && <span className="att-action-note">Expression shown as authored; it is not evaluated in this view.</span>}
              {key === 'assert' && <span className="att-action-note">Assertion expression shown as authored; it is not evaluated in this view.</span>}
            </dd>
          </div>)}
        </dl>}
        {type === 'flow' && <div className="att-flow-target" aria-live="polite">
          {target?.state === 'resolved' && selfTarget
            ? <p role="status">This Flow references itself. Recursive Flow navigation is disabled.</p>
            : target?.state === 'resolved'
              ? <button type="button" className="button button-quiet" onClick={() => onOpenFlow(target.resourceId!)}>Open referenced Flow · {target.logicalId}</button>
              : <p role="status">Referenced Flow is unresolved or invalid; navigation is unavailable.</p>}
          <p>Opening a Flow navigates to its own action inspector; nested actions are not expanded here.</p>
        </div>}
        {extraProperties.length > 0 && <section className="att-action-additional" aria-label="Additional action properties">
          <h4>Additional properties</h4>
          <dl className="att-action-property-list">{extraProperties.map(([key, value]) => <div key={key}>
            <dt>{key}</dt><dd><ValueView value={value} label={key} /></dd>
          </div>)}</dl>
        </section>}
        <details className="att-action-advanced">
          <summary>Advanced structured action</summary>
          <pre className="att-action-value">{safeStringify(selected.value)}</pre>
        </details>
      </article>
    </div>
    <details className="att-action-advanced att-action-definition-advanced">
      <summary>Advanced structured definition</summary>
      <pre className="att-action-value">{safeStringify(definition)}</pre>
    </details>
  </section>;
}
