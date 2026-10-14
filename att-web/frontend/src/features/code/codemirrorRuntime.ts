import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { foldGutter, foldKeymap, indentOnInput, bracketMatching, defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { searchKeymap, openSearchPanel } from '@codemirror/search';
import { EditorState, type Extension } from '@codemirror/state';
import { Decoration, EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, ViewPlugin, type DecorationSet } from '@codemirror/view';

export type CodeLanguage = 'yaml' | 'json' | 'xml' | 'sql' | 'shell' | 'text';

export interface EditorOptions {
  value: string;
  language: CodeLanguage;
  readOnly: boolean;
  onChange(value: string): void;
  onFileReference?(path: string, origin?: string): void;
  sourcePath?: string;
  suggestions?: string[];
}

interface AttToken { from: number; to: number; kind: 'context' | 'expression' | 'testdata' | 'file'; path?: string; safe: boolean }
function safeFileLocator(path: string, sourcePath?: string): boolean {
  if (!path || path.startsWith('/') || path.includes('\\') || path.includes('\0') || /[\u0000-\u001f\u007f]/.test(path) || /^[a-z][a-z\d+.-]*:/i.test(path)) return false;
  const segments = path.split('/');
  if (segments.some(segment => segment === '' && path !== '')) return false;
  const resolved = path.startsWith('./') || path.startsWith('../')
    ? (sourcePath ? sourcePath.split('/').slice(0, -1) : [])
    : [];
  for (const segment of segments) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') { if (!resolved.length) return false; resolved.pop(); }
    else resolved.push(segment);
  }
  return resolved.length > 0;
}
function attTokens(text: string, sourcePath?: string): AttToken[] {
  const tokens: AttToken[] = [];
  for (let index = 0; index < text.length - 2; index++) {
    const prefix = text[index];
    if (!['$', '#', '@', '&'].includes(prefix) || text[index + 1] !== '{') continue;
    if (index > 0 && text[index - 1] === '\\') continue;
    let end = index + 2;
    let nested = 0;
    let escaped = false;
    for (; end < text.length; end++) {
      const char = text[end];
      if (escaped) { escaped = false; continue; }
      if (char === '\\') { escaped = true; continue; }
      if (char === '{') nested++;
      else if (char === '}') { if (nested === 0) break; nested--; }
    }
    if (end >= text.length) continue;
    const kind = prefix === '$' ? 'context' : prefix === '#' ? 'expression' : prefix === '@' ? 'testdata' : 'file';
    const body = text.slice(index + 2, end);
    const safe = kind !== 'file' || safeFileLocator(body, sourcePath);
    tokens.push({ from: index, to: end + 1, kind, path: kind === 'file' && safe ? body : undefined, safe });
    index = end;
  }
  return tokens;
}

function expressionDecorations(sourcePath?: string) { return ViewPlugin.fromClass(class {
  decorations: DecorationSet;
  constructor(view: EditorView) { this.decorations = this.build(view); }
  update(update: { docChanged: boolean; viewportChanged: boolean; view: EditorView }) {
    if (update.docChanged || update.viewportChanged) this.decorations = this.build(update.view);
  }
  private build(view: EditorView): DecorationSet {
    const decorations = [];
    for (const range of view.visibleRanges) {
      const text = view.state.doc.sliceString(range.from, range.to);
      for (const token of attTokens(text, sourcePath)) {
        const attributes: Record<string, string> = {};
        let className = `att-expression att-expression--${token.kind}`;
        if (token.kind === 'file') {
          if (token.safe && token.path) {
            className += ' att-expression--link';
            attributes['data-att-file'] = token.path;
            attributes.role = 'link';
            attributes.tabindex = '0';
            attributes['aria-label'] = `Open package file ${token.path}`;
          } else {
            className += ' att-expression--unsafe-file';
            attributes['aria-label'] = 'Unsafe package file reference';
          }
        }
        decorations.push(Decoration.mark({ class: className, attributes }).range(range.from + token.from, range.from + token.to));
      }
    }
    return Decoration.set(decorations, true);
  }
}, { decorations: plugin => plugin.decorations }); }

function testdataCompletionSource(suggestions: string[]) {
  return (context: CompletionContext) => {
    const word = context.matchBefore(/@\{[^}\s]*/);
    if (!word || (!word.text && !context.explicit)) return null;
    const prefix = word.text.slice(2);
    return { from: word.from + 2, options: suggestions.filter(item => item.startsWith(prefix)).map(label => ({ label, type: 'constant' })) };
  };
}

async function languageExtension(language: CodeLanguage): Promise<Extension> {
  switch (language) {
    case 'yaml': return (await import('@codemirror/lang-yaml')).yaml();
    case 'json': return (await import('@codemirror/lang-json')).json();
    case 'xml': return (await import('@codemirror/lang-xml')).xml();
    case 'sql': return (await import('@codemirror/lang-sql')).sql();
    case 'shell': {
      const [{ shell }, { StreamLanguage }] = await Promise.all([
        import('@codemirror/legacy-modes/mode/shell'),
        import('@codemirror/language'),
      ]);
      return StreamLanguage.define(shell);
    }
    default: return [];
  }
}

export async function createEditor(host: HTMLElement, options: EditorOptions): Promise<EditorView> {
  const language = await languageExtension(options.language);
  const extensions: Extension[] = [
    lineNumbers(), foldGutter(), highlightActiveLineGutter(), highlightActiveLine(), drawSelection(),
    history(), indentOnInput(), bracketMatching(), syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    expressionDecorations(options.sourcePath),
    EditorView.updateListener.of(update => { if (update.docChanged && !options.readOnly) options.onChange(update.state.doc.toString()); }),
    EditorView.domEventHandlers({
      click(event) {
        const target = event.target instanceof HTMLElement ? event.target.closest<HTMLElement>('[data-att-file]') : null;
        const path = target?.dataset.attFile;
        if (path && safeFileLocator(path, options.sourcePath)) { options.onFileReference?.(path, options.sourcePath); return true; }
        return false;
      },
      keydown(event) {
        const target = event.target instanceof HTMLElement ? event.target.closest<HTMLElement>('[data-att-file]') : null;
        const path = target?.dataset.attFile;
        if (path && safeFileLocator(path, options.sourcePath) && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault(); options.onFileReference?.(path, options.sourcePath); return true;
        }
        return false;
      },
    }),
    keymap.of([...defaultKeymap, ...historyKeymap, ...foldKeymap, ...searchKeymap, indentWithTab]),
    EditorState.readOnly.of(options.readOnly),
    EditorView.editable.of(!options.readOnly),
    language,
    options.readOnly ? [] : autocompletion({ override: [testdataCompletionSource(options.suggestions || [])] }),
    EditorView.theme({
      '&': { height: '100%', fontSize: '0.875rem' },
      '.cm-scroller': { overflow: 'auto', fontFamily: 'var(--att-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace)' },
      '.cm-content': { padding: '0.75rem 0', minHeight: '100%' },
      '.cm-line': { padding: '0 0.75rem' },
      '.cm-gutters': { backgroundColor: 'var(--att-color-surface-subtle, #f5f7fa)', color: 'var(--att-color-muted, #667085)', border: 'none' },
      '.cm-activeLine': { backgroundColor: 'color-mix(in srgb, var(--att-color-primary, #0866c6) 7%, transparent)' },
      '.cm-activeLineGutter': { backgroundColor: 'color-mix(in srgb, var(--att-color-primary, #0866c6) 10%, transparent)' },
      '.cm-foldGutter': { width: '1.2rem' },
      '&.cm-focused': { outline: '2px solid var(--att-color-focus, #3b82f6)', outlineOffset: '-2px' },
    }),
  ];
  return new EditorView({ state: EditorState.create({ doc: options.value, extensions }), parent: host });
}

export function focusSearch(view: EditorView): void { openSearchPanel(view); }
export function jumpToLine(view: EditorView, line: number): boolean {
  if (!Number.isSafeInteger(line) || line < 1 || line > view.state.doc.lines) return false;
  const position = view.state.doc.line(line).from;
  view.dispatch({ selection: { anchor: position }, effects: EditorView.scrollIntoView(position, { y: 'center' }) });
  view.focus();
  return true;
}
