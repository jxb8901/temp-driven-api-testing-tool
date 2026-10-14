import { useEffect, useRef, useState } from 'react';
import type { EditorView } from '@codemirror/view';
import type { CodeLanguage } from './codemirrorRuntime';
import './code-editor.css';

export type { CodeLanguage } from './codemirrorRuntime';

export interface CodeEditorProps {
  value: string;
  language: CodeLanguage;
  label: string;
  readOnly?: boolean;
  onChange?: (value: string) => void;
  onFileReference?: (path: string, origin?: string) => void;
  sourcePath?: string;
  suggestions?: string[];
  maxCharacters?: number;
  className?: string;
}

async function formatted(value: string, language: CodeLanguage): Promise<string | null> {
  try {
    if (language === 'json') return JSON.stringify(JSON.parse(value), null, 2);
    if (language === 'yaml') {
      const { parseDocument } = await import('yaml');
      const document = parseDocument(value, { uniqueKeys: false });
      if (document.errors.length) return null;
      return document.toString({ lineWidth: 100 });
    }
  } catch { return null; }
  return null;
}

export function CodeEditor({ value, language, label, readOnly = true, onChange, onFileReference, sourcePath, suggestions = [], maxCharacters = 200_000, className = '' }: CodeEditorProps) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const syncingValue = useRef(false);
  const onFileRef = useRef(onFileReference);
  const latestValue = useRef(value.length > maxCharacters ? value.slice(0, maxCharacters) : value);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('Loading code viewer…');
  const [fullscreen, setFullscreen] = useState(false);
  const [line, setLine] = useState('');
  const [readyKey, setReadyKey] = useState('');
  const bounded = value.length > maxCharacters ? value.slice(0, maxCharacters) : value;
  latestValue.current = bounded;
  const suggestionsKey = suggestions.join('\u0000');
  const editorKey = JSON.stringify([language, readOnly, sourcePath, suggestionsKey]);
  useEffect(() => { onChangeRef.current = onChange; onFileRef.current = onFileReference; }, [onChange, onFileReference]);

  useEffect(() => {
    let cancelled = false;
    let created: EditorView | null = null;
    const editorHost = host.current;
    setReadyKey(''); setError(''); setStatus('Loading syntax-aware viewer…');
    if (!editorHost) return;
    import('./codemirrorRuntime').then(runtime => {
      if (cancelled || host.current !== editorHost) return null;
      return runtime.createEditor(editorHost, {
      value: latestValue.current, language, readOnly,
      onChange: next => { latestValue.current = next; if (!syncingValue.current) onChangeRef.current?.(next); },
      onFileReference: (path, origin) => onFileRef.current?.(path, origin),
      sourcePath,
      suggestions,
    });
    }).then(instance => {
      if (!instance) return;
      if (cancelled) { instance.destroy(); return; }
      const currentValue = latestValue.current;
      if (instance.state.doc.toString() !== currentValue) {
        syncingValue.current = true;
        try { instance.dispatch({ changes: { from: 0, to: instance.state.doc.length, insert: currentValue } }); }
        finally { syncingValue.current = false; }
      }
      created = instance; view.current = instance; setStatus('Code viewer ready.');
      setReadyKey(editorKey);
    }).catch(() => { if (!cancelled) { setError('The code viewer could not be loaded.'); setStatus('Code viewer unavailable.'); } });
    return () => { cancelled = true; created?.destroy(); if (view.current === created) view.current = null; };
  }, [language, readOnly, sourcePath, suggestionsKey, editorKey]);

  useEffect(() => {
    const current = view.current;
    if (!current || current.state.doc.toString() === bounded) return;
    syncingValue.current = true;
    try { current.dispatch({ changes: { from: 0, to: current.state.doc.length, insert: bounded } }); }
    finally { syncingValue.current = false; }
  }, [bounded]);

  async function copy() {
    try { await navigator.clipboard.writeText(bounded); setStatus('Copied displayed code.'); }
    catch { setStatus('Copy is unavailable in this browser context.'); }
  }
  function goToLine() {
    const parsed = Number(line);
    if (!Number.isSafeInteger(parsed) || parsed < 1 || !view.current || parsed > view.current.state.doc.lines) { setStatus('Enter a line number shown in this file.'); return; }
    import('./codemirrorRuntime').then(runtime => { if (view.current && runtime.jumpToLine(view.current, parsed)) setStatus(`Moved to line ${parsed}.`); });
  }
  async function format() {
    const result = await formatted(view.current?.state.doc.toString() || bounded, language);
    if (!result || !view.current) { setStatus(`Could not format this ${language.toUpperCase()} document; the draft is unchanged.`); return; }
    view.current.dispatch({ changes: { from: 0, to: view.current.state.doc.length, insert: result } });
    onChangeRef.current?.(result);
    setStatus('Formatted the current draft.');
  }

  return <section className={`att-code-editor ${fullscreen ? 'is-fullscreen' : ''} ${className}`} aria-label={label}>
    <div className="att-code-toolbar">
      <span className="att-code-language">{language === 'text' ? 'Plain text' : language.toUpperCase()}</span>
      <button type="button" onClick={() => void copy()} disabled={!bounded}>Copy</button>
      <button type="button" onClick={() => {
        const current = view.current;
        if (!current) return;
        import('./codemirrorRuntime').then(runtime => { if (view.current === current) runtime.focusSearch(current); });
      }} disabled={readyKey !== editorKey || !view.current}>Find</button>
      <label className="att-code-goto">Go to line <input type="number" min="1" value={line} onChange={event => setLine(event.currentTarget.value)} onKeyDown={event => { if (event.key === 'Enter') goToLine(); }} /></label>
      <button type="button" onClick={goToLine} disabled={!line}>Go</button>
      {!readOnly && (language === 'json' || language === 'yaml') && <button type="button" onClick={() => void format()}>Format</button>}
      <button type="button" aria-pressed={fullscreen} onClick={() => setFullscreen(current => !current)}>{fullscreen ? 'Exit full screen' : 'Expand'}</button>
    </div>
    <p className="att-code-status" role="status" aria-live="polite">{value.length > maxCharacters ? `Showing first ${maxCharacters.toLocaleString()} of ${value.length.toLocaleString()} characters. ` : ''}{status}</p>
    {error && <p className="att-code-error" role="alert">{error}</p>}
    <div ref={host} className="att-code-host" aria-label={label + (readOnly ? ', read only' : ', editable')} />
  </section>;
}

export function detectCodeLanguage(hint: string): CodeLanguage {
  const normalized = hint.toLowerCase().split(/[?#]/, 1)[0];
  if (normalized.endsWith('.yaml') || normalized.endsWith('.yml') || /yaml/.test(normalized)) return 'yaml';
  if (normalized.endsWith('.json') || /json/.test(normalized)) return 'json';
  if (normalized.endsWith('.xml') || /xml/.test(normalized)) return 'xml';
  if (normalized.endsWith('.sql') || /sql/.test(normalized)) return 'sql';
  if (normalized.endsWith('.sh') || normalized.endsWith('.bash') || /shell|bash/.test(normalized)) return 'shell';
  return 'text';
}
