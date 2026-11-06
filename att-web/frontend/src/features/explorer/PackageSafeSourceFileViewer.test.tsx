// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PackageSafeSourceFileViewer } from './PackageSafeSourceFileViewer';
import type { ExplorerApi } from './types';
import { inspectionCache } from '../../inspectionCache';
import { resourceSourceFilePath } from '../../api';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label, fileReferences }: { value: string; label: string; fileReferences?: Array<{ referenceId: string; logicalPath: string }> }) =>
    <pre aria-label={label} data-reference-count={fileReferences?.length}>{value}</pre>,
  detectCodeLanguage: () => 'xml',
}));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function flush() { await act(async () => { await Promise.resolve(); await Promise.resolve(); }); }

describe('PackageSafeSourceFileViewer', () => {
  it('loads by the Server-issued reference and offers safe nested navigation', async () => {
    const referenceId = 'a'.repeat(64);
    const nestedReferenceId = 'b'.repeat(64);
    const getResourceSourceFile = vi.fn(async () => ({ available: true, logicalPath: 'templates/payment/request.xml', format: 'xml',
      text: '<request>&{./headers.json}</request>', fileReferences: [{ referenceId: nestedReferenceId, logicalPath: 'templates/payment/headers.json', available: true }] }));
    const api = { getResourceSourceFile } as unknown as ExplorerApi;
    const onOpenFile = vi.fn();
    await act(async () => root.render(<PackageSafeSourceFileViewer api={api} packageId="payments" filePath="templates/payment/request.xml"
      fileOrigin="templates/payment/flow.yaml" sourceReference={{ type: 'flow', resourceId: 'checkout', referenceId }} onBack={vi.fn()} onOpenFile={onOpenFile} />));
    await flush();
    expect(getResourceSourceFile).toHaveBeenCalledWith({ packageId: 'payments', type: 'flow', resourceId: 'checkout' }, referenceId,
      expect.any(AbortSignal), 'templates/payment/flow.yaml');
    expect(host.querySelector('h1')?.textContent).toBe('templates/payment/request.xml');
    expect(host.querySelector('pre')?.textContent).toContain('&{./headers.json}');
    expect(host.textContent).toContain('View referenced file');
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'View referenced file')?.click());
    expect(onOpenFile).toHaveBeenCalledWith('templates/payment/headers.json', 'templates/payment/request.xml', {
      type: 'flow', resourceId: 'checkout', referenceId: nestedReferenceId,
    });
  });

  it('reports a policy-unavailable file without rendering file contents', async () => {
    const api = { getResourceSourceFile: vi.fn(async () => ({ available: false, reason: 'denied', text: 'must not appear' })) } as unknown as ExplorerApi;
    await act(async () => root.render(<PackageSafeSourceFileViewer api={api} packageId="payments" filePath="private/credential.xml"
      sourceReference={{ type: 'template', resourceId: 'checkout', referenceId: 'c'.repeat(64) }} onBack={vi.fn()} onOpenFile={vi.fn()} />));
    await flush();
    expect(host.textContent).toContain('Source unavailable (denied)');
    expect(host.textContent).not.toContain('must not appear');
    expect(host.textContent).not.toContain('/Users/');
  });

  it('explains that visible file expressions were withheld by safe-source checks', async () => {
    const api = { getResourceSourceFile: vi.fn(async () => ({ available: true, logicalPath: 'templates/payment/request.xml',
      text: '<request>&{missing.xml}</request>', fileReferences: [], fileReferencesUnavailable: true })) } as unknown as ExplorerApi;
    await act(async () => root.render(<PackageSafeSourceFileViewer api={api} packageId="payments" filePath="templates/payment/request.xml"
      sourceReference={{ type: 'template', resourceId: 'checkout', referenceId: 'd'.repeat(64) }} onBack={vi.fn()} onOpenFile={vi.fn()} />));
    await flush();
    expect(host.textContent).toContain('&{missing.xml}');
    expect(host.textContent).toContain('Some file references could not be verified');
    expect(host.textContent).toContain('no file contents were loaded');
  });

  it('invalidates the exact safe-file projection when refreshing', async () => {
    const referenceId = 'e'.repeat(64);
    const identity = { packageId: 'payments', type: 'template' as const, resourceId: 'checkout' };
    const invalidatePath = vi.spyOn(inspectionCache, 'invalidatePath');
    const api = { getResourceSourceFile: vi.fn(async () => ({ available: true, logicalPath: 'templates/request.xml', text: '<request />' })) } as unknown as ExplorerApi;
    await act(async () => root.render(<PackageSafeSourceFileViewer api={api} packageId="payments" filePath="templates/request.xml"
      fileOrigin="templates/checkout.yaml" sourceReference={{ type: 'template', resourceId: 'checkout', referenceId }} onBack={vi.fn()} onOpenFile={vi.fn()} />));
    await flush();
    await act(async () => [...host.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Refresh source')?.click());
    expect(invalidatePath).toHaveBeenCalledWith(resourceSourceFilePath(identity, referenceId, 'templates/checkout.yaml'));
  });
});
