// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAccessibleConfirmation } from './useAccessibleConfirmation';
import { saveUiLocalePreference } from '../../uiLocale';

declare global { var IS_REACT_ACT_ENVIRONMENT: boolean; }
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;
beforeEach(() => { host = document.createElement('div'); document.body.append(host); root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount()); host.remove(); saveUiLocalePreference('browser'); });

function Harness() {
  const { confirm, dialog } = useAccessibleConfirmation();
  const [result, setResult] = useState('pending');
  return <><button type="button" onClick={() => void confirm({ title: 'Publish source?', message: 'This changes Package source.', confirmLabel: 'Publish' })
    .then(value => setResult(value ? 'confirmed' : 'cancelled'))}>Open confirmation</button>{dialog}<output>{result}</output></>;
}

function LocalizedHarness() {
  const { confirm, dialog } = useAccessibleConfirmation();
  return <><button type="button" onClick={() => void confirm({ title: 'Publish reviewed source?',
    message: 'Publish the reviewed draft for packages/payments/config.yaml. This changes the Package source used across Packages.',
    confirmLabel: 'Publish source' })}>Open confirmation</button>{dialog}</>;
}

describe('useAccessibleConfirmation', () => {
  it('exposes a modal confirmation and resolves cancellation without action', async () => {
    await act(async () => root.render(<Harness />));
    await act(async () => host.querySelector('button')!.click());
    const dialog = host.querySelector<HTMLElement>('[role="alertdialog"]');
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
    expect(dialog?.textContent).toContain('This changes Package source.');
    await act(async () => host.querySelector<HTMLButtonElement>('.att-confirm-actions button:first-child')!.click());
    expect(host.querySelector('output')?.textContent).toBe('cancelled');
    expect(host.querySelector('[role="alertdialog"]')).toBeNull();
  });

  it('resolves explicit approval from the labelled primary action', async () => {
    await act(async () => root.render(<Harness />));
    await act(async () => host.querySelector('button')!.click());
    await act(async () => host.querySelector<HTMLButtonElement>('.att-confirm-actions button:last-child')!.click());
    expect(host.querySelector('output')?.textContent).toBe('confirmed');
  });

  it('localizes confirmation copy while preserving dynamic paths', async () => {
    saveUiLocalePreference('zh-Hant');
    await act(async () => root.render(<LocalizedHarness />));
    await act(async () => host.querySelector('button')!.click());
    expect(host.querySelector('[role="alertdialog"] h2')?.textContent).toBe('要發布已檢閱的來源嗎？');
    expect(host.querySelector('[role="alertdialog"] p')?.textContent)
      .toBe('發布 packages/payments/config.yaml 的已檢閱草稿。此操作會變更 Package 來源（所有 Package 共用）。');
    expect(host.querySelector('.att-confirm-actions button:last-child')?.textContent).toBe('發布來源');
  });
});
