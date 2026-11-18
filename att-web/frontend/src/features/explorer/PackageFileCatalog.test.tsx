// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PackageFileCatalog } from './PackageFileCatalog';
import type { ExplorerApi } from './types';

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function settle() { await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); }); }
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
  it('paginates resource discovery, filters verified paths, and opens only a Server-issued reference', async () => {
    const listResources = vi.fn(async (_packageId: string, query: { cursor?: string }) => query.cursor
      ? { items: [{ type: 'flow' as const, resourceId: 'flow-1', logicalId: 'flow-1' }], total: 2, nextCursor: null }
      : { items: [{ type: 'template' as const, resourceId: 'template-1', logicalId: 'template-1' }], total: 2, nextCursor: 'page-2' });
    const getSafeSource = vi.fn(async (identity: { type: string; resourceId: string }) => ({ available: true, text: 'safe source', fileReferences: identity.type === 'flow'
      ? [{ referenceId: 'b'.repeat(64), logicalPath: 'data/records.csv', available: false }, { referenceId: 'c'.repeat(64), logicalPath: 'templates/input.json', available: true }]
      : [{ referenceId: 'a'.repeat(64), logicalPath: 'templates/input.json', available: true }] }));
    const api = { listResources, getSafeSource } as unknown as ExplorerApi;
    const onOpenFile = vi.fn();
    await act(async () => root.render(<PackageFileCatalog api={api} packageId="payments" onOpenFile={onOpenFile} />));
    click(button('Scan resource sources')); await settle();
    expect(listResources).toHaveBeenCalledWith('payments', { type: 'all', limit: 25 }, expect.any(AbortSignal));
    expect(host.textContent).toContain('templates/input.json');
    click(button('Scan next resource page')); await settle();
    expect(listResources).toHaveBeenLastCalledWith('payments', { type: 'all', limit: 25, cursor: 'page-2' }, expect.any(AbortSignal));
    expect(host.textContent).toContain('2 verified references');
    expect(host.textContent).toContain('data/records.csv');
    const filter = host.querySelector<HTMLInputElement>('input[placeholder="Path or referencing resource"]');
    if (!filter) throw new Error('File filter was not rendered.');
    setValue(filter, 'records.csv');
    expect(host.textContent).toContain('data/records.csv');
    expect([...host.querySelectorAll('button')].some(item => item.textContent?.trim() === 'Open file')).toBe(false);
    setValue(filter, 'templates/input.json');
    click(button('Open file'));
    expect(onOpenFile).toHaveBeenCalledWith('templates/input.json', undefined, {
      type: 'template', resourceId: 'template-1', referenceId: 'a'.repeat(64),
    });
  });
});
