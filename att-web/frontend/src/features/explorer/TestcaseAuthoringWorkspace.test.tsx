// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestcaseAuthoringWorkspace } from './TestcaseAuthoringWorkspace';
import type { ExplorerApi } from './types';

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
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

describe('TestcaseAuthoringWorkspace', () => {
  it('stages a paired upload and requires an explicit hash-bound snapshot action', async () => {
    const draft = { changeSetId: 'cs-case', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments', state: 'draft',
      baseSourceRevision: 'base-1', registryRevision: 'registry-1', changedFiles: [] };
    const generated = { ...draft, snapshotGenerated: true, snapshotPath: 'cases/case-01.xml' };
    const revisionRead = vi.fn(async () => ({ available: true, present: true, logicalPath: 'package.yaml', format: 'yaml',
      text: 'must not be rendered', sourceEtag: 'etag-1', revision: 'revision-1', baseSourceRevision: 'base-1', registryRevision: 'registry-1' }));
    const stage = vi.fn(async () => draft);
    const generate = vi.fn(async () => generated);
    const api = { getManagedPackageAuthoringSource: revisionRead, stageSourceChangeSet: stage,
      generateTestcaseSnapshot: generate } as unknown as ExplorerApi;
    const digest = new Uint8Array(32).fill(0xab).buffer;
    vi.stubGlobal('crypto', { subtle: { digest: vi.fn(async () => digest) } });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<TestcaseAuthoringWorkspace api={api} packageId="payments" sourcePath="cases/case-01.xml" />));
    await settle();
    expect(revisionRead).toHaveBeenCalledWith('payments', { kind: 'manifest' }, expect.any(AbortSignal));
    expect(host.textContent).not.toContain('must not be rendered');
    const input = host.querySelector<HTMLInputElement>('input[aria-label="Upload Testcase workbook and sidecar"]');
    if (!input) throw new Error('Testcase paired upload control was not rendered.');
    const workbook = new File(['PK workbook'], 'case-01.xlsx');
    const sidecar = new File(['columns:\n  id: id\n'], 'case-01.yaml');
    Object.defineProperty(workbook, 'arrayBuffer', { value: async () => new TextEncoder().encode('PK workbook').buffer });
    Object.defineProperty(sidecar, 'arrayBuffer', { value: async () => new TextEncoder().encode('columns:\n  id: id\n').buffer });
    Object.defineProperty(input, 'files', { configurable: true, value: [workbook, sidecar] });
    await act(async () => input.dispatchEvent(new Event('change', { bubbles: true })));
    await settle();
    expect(stage).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 'base-1', 'registry-1', [
      { operation: 'replace', path: 'cases/case-01.xlsx', contentBase64: btoa('PK workbook') },
      { operation: 'replace', path: 'cases/case-01.yaml', content: 'columns:\n  id: id\n' },
    ], expect.any(AbortSignal));
    expect(generate).not.toHaveBeenCalled();
    click(button('Generate Testcase snapshot')); await settle();
    expect(generate).toHaveBeenCalledWith('payments', 'cs-case', {
      expectedSourceRevision: 'base-1', workbookPath: 'cases/case-01.xlsx',
      expectedWorkbookHash: 'ab'.repeat(32), expectedSidecarHash: 'ab'.repeat(32),
    }, expect.any(AbortSignal));
    expect(host.textContent).toContain('Testcase snapshot cases/case-01.xml generated');
  });

  it('creates a new Testcase from a Package-relative XML path', async () => {
    const draft = { changeSetId: 'cs-new', domain: 'PACKAGE_SOURCE' as const, scopeId: 'payments', state: 'draft',
      baseSourceRevision: 'base-1', registryRevision: 'registry-1', changedFiles: [] };
    const stage = vi.fn(async () => draft);
    const api = { getManagedPackageAuthoringSource: vi.fn(async () => ({ available: true, present: true,
      logicalPath: 'package.yaml', format: 'yaml', text: '', sourceEtag: 'etag-1', revision: 'revision-1',
      baseSourceRevision: 'base-1', registryRevision: 'registry-1' })), stageSourceChangeSet: stage } as unknown as ExplorerApi;
    vi.stubGlobal('crypto', { subtle: { digest: vi.fn(async () => new Uint8Array(32).fill(0x12).buffer) } });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<TestcaseAuthoringWorkspace api={api} packageId="payments" />));
    await settle();
    const path = host.querySelector<HTMLInputElement>('input[aria-label="New Testcase XML path"]');
    const input = host.querySelector<HTMLInputElement>('input[aria-label="Upload Testcase workbook and sidecar"]');
    if (!path || !input) throw new Error('New Testcase controls were not rendered.');
    setValue(path, 'cases/case-new.xml');
    const workbook = new File(['workbook'], 'case-new.xlsx');
    const sidecar = new File(['columns: {}\n'], 'case-new.yaml');
    Object.defineProperty(workbook, 'arrayBuffer', { value: async () => new TextEncoder().encode('workbook').buffer });
    Object.defineProperty(sidecar, 'arrayBuffer', { value: async () => new TextEncoder().encode('columns: {}\n').buffer });
    Object.defineProperty(input, 'files', { configurable: true, value: [workbook, sidecar] });
    await act(async () => input.dispatchEvent(new Event('change', { bubbles: true })));
    await settle();
    expect(stage).toHaveBeenCalledWith('PACKAGE_SOURCE', 'payments', 'base-1', 'registry-1', [
      { operation: 'create', path: 'cases/case-new.xlsx', contentBase64: btoa('workbook') },
      { operation: 'create', path: 'cases/case-new.yaml', content: 'columns: {}\n' },
    ], expect.any(AbortSignal));
  });
});
