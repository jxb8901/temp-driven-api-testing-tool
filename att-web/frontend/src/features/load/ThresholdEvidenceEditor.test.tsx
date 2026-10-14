// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LoadModel } from './types';
import { ThresholdEvidenceEditor } from './ThresholdEvidenceEditor';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, onChange }: { value: string; label: string; onChange?: (value: string) => void }) =>
    <textarea aria-label={label} value={value} onChange={event => onChange?.(event.currentTarget.value)} />,
}));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

async function setTextArea(label: string, value: string) {
  const textarea = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="' + label + '"]')!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(textarea, value);
    textarea.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function setInput(input: HTMLInputElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

function renderEditor(model: LoadModel, callbacks: {
  onAggregateChange?: (value: string) => void;
  onEvidenceChange?: (value: string) => void;
  onValidityChange?: (key: string, value: string) => void;
  aggregate?: string;
  evidence?: string;
} = {}) {
  const onAggregateChange = callbacks.onAggregateChange || vi.fn();
  const onEvidenceChange = callbacks.onEvidenceChange || vi.fn();
  const onValidityChange = callbacks.onValidityChange || vi.fn();
  return {
    onAggregateChange, onEvidenceChange, onValidityChange,
    element: <ThresholdEvidenceEditor model={model} aggregate={callbacks.aggregate ?? '{}'} workloads={[]} evidence={callbacks.evidence ?? '{}'}
      onAggregateChange={onAggregateChange} onWorkloadChange={vi.fn()} onEvidenceChange={onEvidenceChange} onValidityChange={onValidityChange} />,
  };
}

describe('ThresholdEvidenceEditor', () => {
  it('adds a metric with a valid form-first default', async () => {
    const view = renderEditor('virtualUsers');
    await act(async () => root.render(view.element));
    const add = [...host.querySelectorAll('button')].find(button => button.textContent === 'Add threshold')!;
    await act(async () => add.click());
    expect(view.onAggregateChange).toHaveBeenCalledWith(JSON.stringify({ errorRate: '<= 1%' }, null, 2));
  });

  it('keeps the last valid thresholds when Advanced JSON is semantically invalid', async () => {
    const view = renderEditor('virtualUsers');
    await act(async () => root.render(view.element));
    await setTextArea('aggregate thresholds advanced JSON', '{"droppedRate":"<= 1%"}');
    expect(view.onAggregateChange).not.toHaveBeenCalled();
    expect(view.onValidityChange).toHaveBeenCalledWith('threshold-aggregate', expect.stringContaining('Arrival Rate'));
    expect(host.querySelector<HTMLSelectElement>('.att-threshold-scope-picker select')?.disabled).toBe(true);
    expect([...host.querySelectorAll('button')].find(button => button.textContent === 'Add threshold')?.disabled).toBe(false);
    await setTextArea('aggregate thresholds advanced JSON', '{}');
    expect(view.onAggregateChange).toHaveBeenCalledWith('{}');
  });

  it('rejects invalid evidence values without replacing the last valid settings', async () => {
    const view = renderEditor('virtualUsers');
    await act(async () => root.render(view.element));
    await setTextArea('Evidence settings advanced JSON', '{"sampleRate":1.2}');
    expect(view.onEvidenceChange).not.toHaveBeenCalled();
    expect(view.onValidityChange).toHaveBeenCalledWith('evidence', expect.stringContaining('between 0 and 1'));
  });

  it('lets users correct a temporarily invalid threshold in the same form row', async () => {
    const view = renderEditor('virtualUsers', { aggregate: '{"p95":"<= 500ms"}' });
    await act(async () => root.render(view.element));
    const input = host.querySelector<HTMLInputElement>('.att-threshold-row input')!;

    await setInput(input, '');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('supported unit');
    expect(input.disabled).toBe(false);
    expect(view.onAggregateChange).not.toHaveBeenCalled();

    await setInput(input, '750');
    expect(view.onAggregateChange).toHaveBeenCalledWith(JSON.stringify({ p95: '<= 750ms' }, null, 2));
    expect(view.onValidityChange).toHaveBeenCalledWith('threshold-aggregate', '');
  });

  it('lets users correct invalid evidence values in the form and preserves unknown JSON keys', async () => {
    const view = renderEditor('virtualUsers', { evidence: '{"mode":"samples","sampleRate":0.25,"custom":{"keep":true}}' });
    await act(async () => root.render(view.element));
    const input = [...host.querySelectorAll<HTMLInputElement>('.att-evidence-form input')]
      .find(candidate => candidate.closest('label')?.textContent?.includes('Success sample rate'))!;

    await setInput(input, '1.2');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('between 0 and 1');
    expect(input.disabled).toBe(false);
    expect(view.onEvidenceChange).not.toHaveBeenCalled();

    await setInput(input, '0.5');
    expect(view.onEvidenceChange).toHaveBeenCalledWith(JSON.stringify({ mode: 'samples', sampleRate: 0.5, custom: { keep: true } }, null, 2));
    expect(view.onValidityChange).toHaveBeenCalledWith('evidence', '');
  });
});
