// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CodeEditor } from './CodeEditor';

const editor = vi.hoisted(() => ({
  text: '',
  emit: null as null | ((value: string) => void),
  creationDelay: null as Promise<void> | null,
}));

vi.mock('./codemirrorRuntime', () => ({
  createEditor: async (_host: HTMLElement, options: { value: string; onChange(value: string): void }) => {
    if (editor.creationDelay) await editor.creationDelay;
    editor.text = options.value;
    editor.emit = value => { editor.text = value; options.onChange(value); };
    const doc = {
      toString: () => editor.text,
      get length() { return editor.text.length; },
      get lines() { return editor.text.split('\n').length; },
    };
    return {
      state: { doc },
      dispatch: (transaction: { changes: { insert: string } }) => {
        editor.text = transaction.changes.insert;
        options.onChange(editor.text);
      },
      destroy: () => {},
    } as never;
  },
  focusSearch: () => {},
  jumpToLine: () => true,
}));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  editor.text = '';
  editor.emit = null;
  editor.creationDelay = null;
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
}

describe('CodeEditor', () => {
  it('does not echo external value syncs, but still forwards user edits', async () => {
    const onChange = vi.fn();
    await act(async () => root.render(<CodeEditor value="{}" language="json" label="Editable JSON" readOnly={false} onChange={onChange} />));
    await flush();

    await act(async () => root.render(<CodeEditor value='{"from":"form"}' language="json" label="Editable JSON" readOnly={false} onChange={onChange} />));
    await flush();
    expect(editor.text).toBe('{"from":"form"}');
    expect(onChange).not.toHaveBeenCalled();

    await act(async () => editor.emit?.('{"from":"editor"}'));
    expect(onChange).toHaveBeenCalledWith('{"from":"editor"}');
  });

  it('uses the latest value after delayed editor initialization and enables Find only when ready', async () => {
    let release!: () => void;
    editor.creationDelay = new Promise<void>(resolve => { release = resolve; });
    await act(async () => root.render(<CodeEditor value="old source" language="yaml" label="Flow source" readOnly />));
    await act(async () => { await Promise.resolve(); });
    const find = [...host.querySelectorAll('button')].find(button => button.textContent === 'Find')!;
    expect(find.disabled).toBe(true);

    await act(async () => root.render(<CodeEditor value="new source" language="yaml" label="Flow source" readOnly />));
    await act(async () => { release(); await Promise.resolve(); await Promise.resolve(); });
    expect(editor.text).toBe('new source');
    expect(find.disabled).toBe(false);
  });
});
