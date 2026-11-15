// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext, type AppShellContextValue } from '../../AppShell';
import { api } from '../../api';
import type { ValidationRecord, ValidationRecordPage } from './types';
import { ValidationHistory } from './ValidationHistory';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const record = (validationId: string, overrides: Partial<ValidationRecord> = {}): ValidationRecord => ({
  validationId, scope: 'package', packageId: 'payments', target: 'payments', environment: 'SIT', status: 'COMPLETED',
  requestId: `request-${validationId}`, submittedAt: '2026-11-15T10:00:00Z', overallStatus: 'PASS', ...overrides,
});
const baseContext = { uiLocale: 'en', authContextKey: 'account-a', inspectionSessionEpoch: 1 } as unknown as AppShellContextValue;

let host: HTMLDivElement;
let root: Root;
let listRecords: ReturnType<typeof vi.spyOn>;
let visibilityDescriptor: PropertyDescriptor | undefined;

beforeEach(() => {
  vi.restoreAllMocks();
  visibilityDescriptor = Object.getOwnPropertyDescriptor(document, 'visibilityState');
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  listRecords = vi.spyOn(api, 'listValidationRecords').mockResolvedValue({ items: [], nextCursor: null });
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove(); vi.useRealTimers(); vi.restoreAllMocks();
  if (visibilityDescriptor) Object.defineProperty(document, 'visibilityState', visibilityDescriptor);
  else Reflect.deleteProperty(document, 'visibilityState');
});

async function settle() {
  await act(async () => { for (let index = 0; index < 6; index += 1) await Promise.resolve(); });
}

async function render(context = baseContext) {
  await act(async () => root.render(<AppShellContext.Provider value={context}>
    <ValidationHistory active supported onOpenRecord={vi.fn()} />
  </AppShellContext.Provider>));
  await settle();
}

function setInput(labelText: string, value: string) {
  const label = [...host.querySelectorAll('label')].find(item => item.textContent?.startsWith(labelText));
  const input = label?.querySelector('input');
  if (!input) throw new Error(`Missing ${labelText} input`);
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function setSelect(labelText: string, value: string) {
  const label = [...host.querySelectorAll('label')].find(item => item.textContent?.startsWith(labelText));
  const select = label?.querySelector('select');
  if (!select) throw new Error(`Missing ${labelText} filter`);
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
}

async function clickButton(name: string) {
  const button = [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === name);
  if (!button) throw new Error(`Missing ${name} button`);
  await act(async () => { button.click(); await Promise.resolve(); });
  await settle();
}

describe('ValidationHistory', () => {
  it('applies status, Package, Environment and scope filters, and follows page cursors', async () => {
    listRecords.mockImplementation(async (input?: Parameters<typeof api.listValidationRecords>[0]) => ({
      items: [record(input?.cursor ? 'page-two' : 'filtered')],
      nextCursor: input?.cursor ? null : 'cursor-two',
    }));
    await render();
    await act(async () => {
      setInput('Package', 'payments');
      setInput('Environment', 'SIT');
      setSelect('Scope', 'package');
      setSelect('Status', 'COMPLETED');
    });
    await settle();
    expect(listRecords).toHaveBeenLastCalledWith({ packageId: 'payments', environment: 'SIT', scope: 'package', status: 'COMPLETED', limit: 50, cursor: undefined }, expect.any(AbortSignal));
    expect(host.textContent).toContain('filtered');

    await clickButton('Next');
    expect(listRecords).toHaveBeenLastCalledWith({ packageId: 'payments', environment: 'SIT', scope: 'package', status: 'COMPLETED', limit: 50, cursor: 'cursor-two' }, expect.any(AbortSignal));
    expect(host.textContent).toContain('page-two');
    expect(host.textContent).toContain('Page 2');

    await clickButton('Previous');
    expect(listRecords).toHaveBeenLastCalledWith({ packageId: 'payments', environment: 'SIT', scope: 'package', status: 'COMPLETED', limit: 50, cursor: undefined }, expect.any(AbortSignal));
  });

  it('clears the prior identity history and filters immediately when the authenticated account changes', async () => {
    listRecords.mockResolvedValue({ items: [record('VAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA')], nextCursor: null });
    await render();
    await act(async () => {
      setInput('Package', 'payments');
      setSelect('Status', 'COMPLETED');
    });
    await settle();
    expect(host.textContent).toContain('VAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');

    let resolveNewPage: ((page: ValidationRecordPage) => void) | undefined;
    listRecords.mockClear();
    listRecords.mockImplementation(() => new Promise(resolve => { resolveNewPage = resolve; }));
    const nextContext = { ...baseContext, authContextKey: 'account-b', inspectionSessionEpoch: 2 } as AppShellContextValue;
    await act(async () => root.render(<AppShellContext.Provider value={nextContext}>
      <ValidationHistory active supported onOpenRecord={vi.fn()} />
    </AppShellContext.Provider>));

    expect(host.textContent).not.toContain('VAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');
    expect((host.querySelector('input[type="search"]') as HTMLInputElement).value).toBe('');
    expect((host.querySelector('select') as HTMLSelectElement).value).toBe('');
    expect(listRecords).toHaveBeenCalledWith({ packageId: undefined, environment: undefined, scope: undefined, status: undefined, limit: 50, cursor: undefined }, expect.any(AbortSignal));

    await act(async () => { resolveNewPage?.({ items: [record('VBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB')], nextCursor: null }); await Promise.resolve(); });
    await settle();
    expect(host.textContent).toContain('VBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB');
    expect(host.textContent).not.toContain('VAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');
  });

  it('refreshes idle page one slowly while visible, pauses while hidden, and stops after a bounded window', async () => {
    vi.useFakeTimers();
    listRecords.mockResolvedValue({ items: [], nextCursor: null });
    await render();
    expect(listRecords).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(29_999); });
    expect(listRecords).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    await settle();
    expect(listRecords).toHaveBeenCalledTimes(2);

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(listRecords).toHaveBeenCalledTimes(2);

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    await settle();
    for (let poll = 0; poll < 20; poll += 1) {
      await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
      await settle();
    }
    expect(listRecords).toHaveBeenCalledTimes(21);
    expect(host.textContent).toContain('Automatic history refresh paused after 10 minutes');
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(listRecords).toHaveBeenCalledTimes(21);
  });

  it('keeps active rows visible while the five-second background refresh is pending', async () => {
    vi.useFakeTimers();
    const activeRecord = record('VAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', { status: 'RUNNING', overallStatus: undefined, phase: 'inspection' });
    const completedRecord: ValidationRecord = { ...activeRecord, status: 'COMPLETED', overallStatus: 'PASS', phase: undefined };
    let resolveRefresh: ((page: ValidationRecordPage) => void) | undefined;
    listRecords.mockResolvedValueOnce({ items: [activeRecord], nextCursor: null })
      .mockImplementationOnce(() => new Promise(resolve => { resolveRefresh = resolve; }));
    await render();
    expect(host.textContent).toContain(activeRecord.validationId);

    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(listRecords).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain(activeRecord.validationId);
    expect(host.textContent).toContain('Refreshing validations…');
    expect(host.textContent).not.toContain('Loading validations…');

    await act(async () => { resolveRefresh?.({ items: [completedRecord], nextCursor: null }); await Promise.resolve(); });
    await settle();
    expect(host.textContent).toContain(completedRecord.validationId);
    expect(host.textContent).toContain('COMPLETED');
  });
});
