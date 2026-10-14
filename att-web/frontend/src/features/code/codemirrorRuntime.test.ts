// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createEditor } from './codemirrorRuntime';

const views: Array<{ destroy(): void }> = [];
afterEach(() => { views.splice(0).forEach(view => view.destroy()); });

describe('CodeMirror safe package-file references', () => {
  it('links a reference only when the Server projected its canonical path and token', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    const open = vi.fn();
    const sourcePath = 'templates/payment/flow.yaml';
    const referenceId = 'f'.repeat(64);
    const view = await createEditor(host, {
      value: 'payload: &{./payload/request.xml}', language: 'yaml', readOnly: true,
      sourcePath, onChange: vi.fn(), onFileReference: open,
      fileReferences: [{ referenceId, logicalPath: 'templates/payment/payload/request.xml' }],
    });
    views.push(view);

    const link = host.querySelector<HTMLElement>('[data-att-reference]');
    expect(link?.dataset.attFile).toBe('./payload/request.xml');
    expect(link?.dataset.attReference).toBe(referenceId);
    link?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(open).toHaveBeenCalledWith('./payload/request.xml', sourcePath, referenceId);

    host.remove();
  });

  it('marks unprojected file references unavailable and keeps them non-interactive', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    const open = vi.fn();
    const view = await createEditor(host, {
      value: 'payload: &{./payload/request.xml}', language: 'yaml', readOnly: true,
      sourcePath: 'config/main.yaml', onChange: vi.fn(), onFileReference: open, fileReferences: [],
    });
    views.push(view);

    const unavailable = host.querySelector<HTMLElement>('.att-expression--unavailable-file');
    expect(unavailable?.getAttribute('aria-label')).toContain('unavailable');
    expect(unavailable?.hasAttribute('data-att-file')).toBe(false);
    unavailable?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(open).not.toHaveBeenCalled();

    host.remove();
  });
});
