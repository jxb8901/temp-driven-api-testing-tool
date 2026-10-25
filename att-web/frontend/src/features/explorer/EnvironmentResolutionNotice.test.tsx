// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { api } from '../../api';
import { EnvironmentResolutionNotice } from './EnvironmentResolutionNotice';
import type { ManifestProjection } from './packageManifest';

vi.mock('../../api', () => ({ api: { getPackageManifest: vi.fn() } }));
let host: HTMLDivElement, root: Root;
beforeEach(() => { vi.resetAllMocks();host = document.createElement('div');document.body.append(host);root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount());host.remove(); });
const projection = (environment: string, environmentOrigin: string): ManifestProjection => ({ packageId: 'p', present: true, canEdit: false, manifestDigest: 'a'.repeat(64), environment, environmentOrigin });
async function render(packageId: string, environment = '') { await act(async () => { root.render(<EnvironmentResolutionNotice packageId={packageId} environment={environment} />); }); }

it('shows the resolved Package default and distinguishes an explicit request', async () => {
  vi.mocked(api.getPackageManifest).mockResolvedValueOnce(projection('UAT', 'package')).mockResolvedValueOnce(projection('SIT', 'request'));
  await render('p');
  expect(host.textContent).toContain('Resolved Environment: UAT · Origin: Package Manifest');
  expect(api.getPackageManifest).toHaveBeenLastCalledWith('p', 'effective', undefined, expect.any(AbortSignal));
  await render('p', 'SIT');
  expect(host.textContent).toContain('Resolved Environment: SIT · Origin: Request');
  expect(api.getPackageManifest).toHaveBeenLastCalledWith('p', 'effective', 'SIT', expect.any(AbortSignal));
});

it('never displays the prior Package resolution after switching Packages', async () => {
  let complete!: (value: ManifestProjection) => void;
  vi.mocked(api.getPackageManifest).mockImplementationOnce(() => new Promise(resolve => { complete = resolve; })).mockResolvedValueOnce(projection('SIT', 'global'));
  await render('a');await render('b');
  await act(async () => { complete(projection('UAT', 'package')); });
  expect(host.textContent).toContain('SIT · Origin: Global configuration');
  expect(host.textContent).not.toContain('UAT');
});

it('does not invent a default when configuration cannot be resolved', async () => {
  vi.mocked(api.getPackageManifest).mockRejectedValue(new Error('unavailable'));
  await render('p');
  expect(host.textContent).toContain('Environment could not be resolved');
  expect(host.textContent).not.toContain('SIT');
});
