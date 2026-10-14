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

function renderEditor(model: LoadModel, callbacks: {
  onAggregateChange?: (value: string) => void;
  onEvidenceChange?: (value: string) => void;
  onValidityChange?: (key: string, value: string) => void;
} = {}) {
  const onAggregateChange = callbacks.onAggregateChange || vi.fn();
  const onEvidenceChange = callbacks.onEvidenceChange || vi.fn();
  const onValidityChange = callbacks.onValidityChange || vi.fn();
  return {
    onAggregateChange, onEvidenceChange, onValidityChange,
    element: <ThresholdEvidenceEditor model={model} aggregate="{}" workloads={[]} evidence="{}"
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
    expect([...host.querySelectorAll('button')].find(button => button.textContent === 'Add threshold')?.disabled).toBe(true);
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
});
