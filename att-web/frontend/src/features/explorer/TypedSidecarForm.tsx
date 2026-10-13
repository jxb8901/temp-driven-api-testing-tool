import { useEffect, useId, useMemo, useState } from 'react';
import type { JsonObject, JsonValue } from './types';

const KEEP_DEFAULT = '$attDebugKeepDefault';
export interface SidecarGroup { key: string; label: string }

export interface TypedSidecarFormProps {
  value: JsonObject;
  groups: SidecarGroup[];
  resetKey: number;
  onChange: (value: JsonObject) => void;
  onValidityChange: (valid: boolean) => void;
}

function isObject(value: JsonValue | undefined): value is { [key: string]: JsonValue } {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}
function isKeepDefault(value: JsonValue | undefined): value is { [KEEP_DEFAULT]: string } {
  return isObject(value) && Object.keys(value).length === 1 && typeof value[KEEP_DEFAULT] === 'string';
}
function valueKind(value: JsonValue): 'string' | 'number' | 'boolean' | 'null' | null {
  if (value === null) return 'null';
  if (typeof value === 'string') return 'string';
  if (typeof value === 'number' && Number.isFinite(value)) return 'number';
  if (typeof value === 'boolean') return 'boolean';
  return null;
}
function safeObjectClone(value: JsonObject): JsonObject {
  return JSON.parse(JSON.stringify(value)) as JsonObject;
}
function replacePath(root: JsonObject, parts: string[], value: JsonValue): JsonObject {
  const next = safeObjectClone(root);
  let parent: Record<string, JsonValue> = next;
  for (const part of parts.slice(0, -1)) {
    const child = Object.hasOwn(parent, part) ? parent[part] : undefined;
    if (!isObject(child)) Object.defineProperty(parent, part, { configurable: true, enumerable: true, writable: true, value: {} });
    parent = Object.getOwnPropertyDescriptor(parent, part)?.value as Record<string, JsonValue>;
  }
  Object.defineProperty(parent, parts[parts.length - 1], { configurable: true, enumerable: true, writable: true, value });
  return next;
}

interface ScalarFieldProps {
  formId: string;
  id: string;
  label: string;
  value: JsonValue;
  onChange: (value: JsonValue) => void;
  onInvalid: (id: string, invalid: boolean) => void;
}

function ScalarField({ formId, id, label, value, onChange, onInvalid }: ScalarFieldProps) {
  const marker = isKeepDefault(value);
  const controlId = `${formId}-${id.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  const [keepDefault, setKeepDefault] = useState(marker);
  const [kind, setKind] = useState(() => marker ? '' : valueKind(value) || 'string');
  const [draft, setDraft] = useState(() => typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? String(value) : '');

  const apply = (nextKind: string, raw = draft) => {
    if (nextKind === 'null') { onChange(null); onInvalid(id, false); return; }
    if (nextKind === 'boolean') { onChange(raw === 'false' ? false : true); onInvalid(id, false); return; }
    if (nextKind === 'number') {
      const trimmed = raw.trim();
      const parsed = trimmed === '' ? Number.NaN : Number(trimmed);
      if (!Number.isFinite(parsed)) { onInvalid(id, true); return; }
      onChange(parsed); onInvalid(id, false); return;
    }
    onChange(raw); onInvalid(id, false);
  };

  const changeKind = (nextKind: string) => {
    setKind(nextKind);
    if (!nextKind) { onInvalid(id, true); return; }
    if (nextKind === 'null') { apply(nextKind); return; }
    if (nextKind === 'boolean') { setDraft('true'); apply(nextKind, 'true'); return; }
    if (nextKind === 'number') {
      const initial = typeof value === 'number' ? String(value) : '';
      setDraft(initial); apply(nextKind, initial); return;
    }
    const initial = typeof value === 'string' ? value : '';
    setDraft(initial); apply(nextKind, initial);
  };

  return <div className="att-sidecar-field">
    <label className="att-sidecar-field-label" htmlFor={`${controlId}-value`}>{label}</label>
    <div className="att-sidecar-field-controls">
      {marker && <label className="att-sidecar-keep-default">
        <input type="checkbox" checked={keepDefault} onChange={event => {
          const keep = event.currentTarget.checked;
          setKeepDefault(keep);
          if (keep) onInvalid(id, false);
          else onInvalid(id, !kind);
        }} />
        Keep hidden package default
      </label>}
      <select aria-label={`${label} value type`} value={kind} disabled={marker && keepDefault}
        onChange={event => changeKind(event.currentTarget.value)}>
        {marker && <option value="">Choose type</option>}
        <option value="string">Text</option><option value="number">Number</option>
        <option value="boolean">Boolean</option><option value="null">Null</option>
      </select>
      {kind === 'null'
        ? <span id={`${controlId}-value`} className="att-sidecar-null" aria-label={`${label}: null`}>null</span>
        : kind === 'boolean'
          ? <select id={`${controlId}-value`} aria-label={`${label} value`} value={draft || 'true'} disabled={marker && keepDefault}
              onChange={event => { setDraft(event.currentTarget.value); apply('boolean', event.currentTarget.value); }}>
            <option value="true">true</option><option value="false">false</option>
          </select>
          : <input id={`${controlId}-value`} type={kind === 'number' ? 'number' : 'text'} step={kind === 'number' ? 'any' : undefined}
              aria-label={`${label} value`} value={draft} disabled={(marker && keepDefault) || !kind}
              onChange={event => { setDraft(event.currentTarget.value); apply(kind, event.currentTarget.value); }} />}
    </div>
  </div>;
}

export function TypedSidecarForm({ value, groups, resetKey, onChange, onValidityChange }: TypedSidecarFormProps) {
  const formId = useId().replace(/:/g, '');
  const [advancedText, setAdvancedText] = useState(() => JSON.stringify(value, null, 2));
  const [advancedError, setAdvancedError] = useState('');
  const [revision, setRevision] = useState(0);
  const [invalidFields, setInvalidFields] = useState<Set<string>>(() => new Set());
  const groupSignature = groups.map(group => group.key).join('|');
  const serializedValue = useMemo(() => JSON.stringify(value, null, 2), [value]);

  useEffect(() => {
    setAdvancedText(serializedValue);
  }, [serializedValue]);
  useEffect(() => {
    setAdvancedText(JSON.stringify(value, null, 2));
    setAdvancedError(''); setInvalidFields(new Set()); setRevision(current => current + 1);
  // resetKey deliberately resets local marker/type errors along with parent-owned values.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetKey, groupSignature]);

  const valid = !advancedError && invalidFields.size === 0;
  useEffect(() => { onValidityChange(valid); }, [valid, onValidityChange]);

  const reportInvalid = (id: string, invalid: boolean) => {
    setInvalidFields(current => {
      const next = new Set(current);
      if (invalid) next.add(id); else next.delete(id);
      return next;
    });
  };

  const handleAdvanced = (text: string) => {
    setAdvancedText(text);
    try {
      const parsed: unknown = JSON.parse(text || '{}');
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Enter a JSON object.');
      setAdvancedError(''); setInvalidFields(new Set()); setRevision(current => current + 1);
      onChange(parsed as JsonObject);
    } catch (reason) {
      setAdvancedError(reason instanceof Error ? reason.message : 'Enter a valid JSON object.');
    }
  };

  return <div className="att-sidecar-form">
    {groups.map(group => {
      const object = isObject(value[group.key]) ? value[group.key] as Record<string, JsonValue> : {};
      const fields = Object.entries(object).filter(([, child]) => isKeepDefault(child) || valueKind(child) !== null);
      if (!fields.length) return null;
      return <fieldset className="att-sidecar-group" key={group.key}>
        <legend>{group.label}</legend>
        {fields.map(([name, child]) => {
          const path = `${group.key}.${name}`;
          return <ScalarField key={`${revision}:${path}`} formId={formId} id={path} label={name} value={child}
            onInvalid={reportInvalid}
            onChange={next => onChange(replacePath(value, [group.key, name], next))} />;
        })}
      </fieldset>;
    })}
    <details className="att-sidecar-advanced">
      <summary>Advanced JSON</summary>
      <label htmlFor={`att-sidecar-advanced-json-${formId}`}>Edit complete business input JSON, including nested values</label>
      <textarea id={`att-sidecar-advanced-json-${formId}`}  spellCheck={false} value={advancedText}
        onChange={event => handleAdvanced(event.currentTarget.value)} aria-invalid={Boolean(advancedError)} />
      {advancedError && <p className="att-field-error" role="alert">{advancedError}</p>}
    </details>
  </div>;
}
