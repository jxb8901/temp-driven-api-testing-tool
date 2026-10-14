// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CodeEditor } from './CodeEditor';

const editor = vi.hoisted(() => ({
  text: '',
  emit: null as null | ((value: string) => void),
}));

vi.mock('./codemirrorRuntime', () => ({
  createEditor: async (_host: HTMLElement, options: { value: string; onChange(value: string): void }) => {
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
});
