// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PackageFileCatalog } from './PackageFileCatalog';
import type { ExplorerApi } from './types';
import { applyUiLocale } from '../../uiLocale';

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); applyUiLocale('en'); });
async function settle(delay = 0) { await act(async () => { await new Promise(resolve => window.setTimeout(resolve, delay)); }); }
function button(name: string): HTMLButtonElement {
  const found = [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === name);
  if (!found) throw new Error(`Button not found: ${name}`);
  return found;
}
function click(element: HTMLElement) { act(() => element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))); }
function setValue(element: HTMLInputElement, value: string) {
  act(() => {
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')?.set?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

describe('PackageFileCatalog', () => {
  it('paginates the revision-fenced Package index, searches the full inventory, and opens indexed references', async () => {
    const listPackageFileIndex = vi.fn(async (_packageId: string, query: { query?: string; cursor?: string }) => {
      if (query.query === 'records.csv') return { items: [{ path: 'data/records.csv', available: false, reason: 'missing', uses: [
        { type: 'flow' as const, resourceId: 'flow-1', label: 'Payment flow', available: false, reason: 'missing', callSites: [{ actionId: 'readRecords' }] },
      ] }], total: 1, nextCursor: null, packageRevision: 'b'.repeat(64), canEdit: true,
        coverage: { state: 'PARTIAL', complete: false, resourcesScanned: 2, resourcesIncomplete: 1, referenceSetsTruncated: 0 } };
      if (query.cursor) return { items: [{ path: 'z/archive.xml', available: true, uses: [
        { type: 'flow' as const, resourceId: 'flow-1', label: 'Payment flow', referenceId: 'c'.repeat(64), originPath: 'templates/flow.yaml', available: true, callSites: [] },
      ] }], total: 2, nextCursor: null, packageRevision: 'a'.repeat(64), canEdit: true,
        coverage: { state: 'COMPLETE', complete: true, resourcesScanned: 2, resourcesIncomplete: 0, referenceSetsTruncated: 0 } };
      return { items: [{ path: 'templates/input.json', available: true, uses: [
        { type: 'template' as const, resourceId: 'template-1', label: 'Payment request', referenceId: 'a'.repeat(64), originPath: 'templates/main.yaml', available: true, callSites: [{ actionId: 'sendRequest' }] },
        { type: 'flow' as const, resourceId: 'flow-1', label: 'Payment flow', referenceId: 'b'.repeat(64), originPath: 'templates/flow.yaml', available: true, callSites: [{ actionId: 'sendPayload' }] },
      ] }], total: 2, nextCursor: 'page-2', packageRevision: 'a'.repeat(64), canEdit: true,
        coverage: { state: 'COMPLETE', complete: true, resourcesScanned: 2, resourcesIncomplete: 0, referenceSetsTruncated: 0 } };
    });
    const listResources = vi.fn();
    const getSafeSource = vi.fn();
    const api = { listPackageFileIndex, listResources, getSafeSource } as unknown as ExplorerApi;
    const onOpenFile = vi.fn();
    const onOpenResource = vi.fn();
    await act(async () => root.render(<PackageFileCatalog api={api} packageId="payments" onOpenFile={onOpenFile} onOpenResource={onOpenResource} />));
    await settle();
    expect(listPackageFileIndex).toHaveBeenCalledWith('payments', { limit: 25 }, expect.any(AbortSignal));
    expect(listResources).not.toHaveBeenCalled();
    expect(getSafeSource).not.toHaveBeenCalled();
    expect(host.textContent).toContain('templates/input.json');
    expect(host.textContent).toContain('Coverage: COMPLETE');
    expect(host.textContent).toContain('source editing available');
    click(button('Load more files')); await settle();
    expect(listPackageFileIndex).toHaveBeenLastCalledWith('payments', { cursor: 'page-2', limit: 25 }, expect.any(AbortSignal));
    expect(host.textContent).toContain('z/archive.xml');
    const filter = host.querySelector<HTMLInputElement>('input[placeholder="Path, resource, or action"]');
    if (!filter) throw new Error('File filter was not rendered.');
    setValue(filter, 'records.csv');
    await settle(220);
    expect(host.textContent).toContain('data/records.csv');
    expect(host.textContent).toContain('Coverage: PARTIAL');
    expect([...host.querySelectorAll('button')].some(item => item.textContent?.trim() === 'Open file')).toBe(false);
    setValue(filter, 'templates/input.json');
    await settle(220);
    click([...host.querySelectorAll('button')].find(item => item.textContent?.includes('sendRequest'))!);
    expect(onOpenResource).toHaveBeenCalledWith({ type: 'template', resourceId: 'template-1' }, 'sendRequest');
    click(button('Open file'));
    expect(onOpenFile).toHaveBeenCalledWith('templates/input.json', 'templates/main.yaml', {
      type: 'template', resourceId: 'template-1', referenceId: 'a'.repeat(64),
    });
  });

  it('does not fall back to per-resource fan-out when the Server index endpoint is unavailable', async () => {
    const listResources = vi.fn();
    const api = { listResources, getSafeSource: vi.fn() } as unknown as ExplorerApi;
    await act(async () => root.render(<PackageFileCatalog api={api} packageId="payments" onOpenFile={() => {}} />));
    await settle();
    expect(host.textContent).toContain('does not provide the revision-fenced Package File index');
    expect(listResources).not.toHaveBeenCalled();
  });

  it('localizes complete and partial index coverage with explicit read access state', async () => {
    const api = { listPackageFileIndex: vi.fn(async () => ({ items: [], total: 0, nextCursor: null,
      packageRevision: 'a'.repeat(64), canEdit: false,
      coverage: { state: 'PARTIAL', complete: false, resourcesScanned: 2, resourcesIncomplete: 1, referenceSetsTruncated: 1 },
    })) } as unknown as ExplorerApi;
    await act(async () => root.render(<PackageFileCatalog api={api} packageId="payments" locale="zh-Hant" onOpenFile={() => {}} />));
    await settle();
    applyUiLocale('zh-Hant');
    expect(host.textContent).toContain('0／0 個索引檔案 · 唯讀存取');
    expect(host.textContent).toContain('涵蓋範圍：部分。已索引 2 個 Template／Flow 來源。');
    expect(host.textContent).toContain('1 個來源無法完整索引；搜尋結果可能缺少參照。');
    expect(host.textContent).toContain('1 組參照達到 Server 安全上限。');
    expect(host.textContent).not.toContain('Coverage: PARTIAL');
    expect(host.textContent).not.toContain('read-only access');
  });
});
