// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { api } from './api';
import { artifactApi } from './features/load/artifactApi';
import { parseRoute } from './router';
import type { JobSummary } from './types';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
}

describe('App Dashboard job refresh', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.setItem('att-ui-language', 'en');
    localStorage.setItem('att-ui-theme', 'light');
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    vi.spyOn(api, 'getVersion').mockResolvedValue({ apiVersion: '1', inlineLoadEnabled: false });
    vi.spyOn(api, 'getPackages').mockResolvedValue({ items: [] });
    vi.spyOn(api, 'getAuthCapabilities').mockResolvedValue({ configurationAdmin: false, capabilities: { operate: true, accountManage: true } });
    vi.spyOn(api, 'listJobs').mockResolvedValue([]);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.useRealTimers();
    vi.restoreAllMocks();
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
  });

  async function settle() {
    await act(async () => {
      for (let i = 0; i < 6; i++) await Promise.resolve();
    });
  }

  function clickNavigation(name: 'dashboard' | 'jobs') {
    const link = host.querySelector(`[data-nav="${name}"]`);
    if (!link) throw new Error(`Missing ${name} navigation link`);
    act(() => link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })));
  }

  it('preserves Workspace while a different historical Job is opened and reloaded', async () => {
    sessionStorage.setItem('att.workspace.context', JSON.stringify({packageId:'payments', environment:'UAT'}));
    window.history.replaceState(null, '', window.location.pathname + '#/jobs/J1234567890ABCDEF');
    vi.spyOn(api, 'getDeclaredConfiguration').mockResolvedValue({environments:[{name:'UAT',state:'active'}]});
    vi.spyOn(api, 'getJob').mockResolvedValue({jobId:'J1234567890ABCDEF',command:'run',packageId:'legacy',environment:'SIT',status:'PASS',targetIdentity:{type:'flow',id:'checkout'}});
    await act(async () => root.render(<App />)); await settle();
    const packageSelect = host.querySelector<HTMLSelectElement>('[aria-label="Workspace Package"]')!;
    const envSelect = host.querySelector<HTMLSelectElement>('[aria-label="Workspace Environment"]')!;
    expect(packageSelect.value).toBe('payments'); expect(envSelect.value).toBe('UAT');
    expect(packageSelect.disabled).toBe(true); expect(envSelect.disabled).toBe(true);
    expect(JSON.parse(sessionStorage.getItem('att.workspace.context')!)).toEqual({packageId:'payments',environment:'UAT'});
    act(() => root.unmount()); root=createRoot(host);
    await act(async () => root.render(<App />)); await settle();
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Workspace Package"]')?.value).toBe('payments');
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Workspace Environment"]')?.value).toBe('UAT');
  });

  it('seeds a new RUN from the selected Workspace Package and Environment', async () => {
    sessionStorage.setItem('att.workspace.context', JSON.stringify({ packageId: 'payments', environment: 'UAT' }));
    window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/run');
    vi.mocked(api.getPackages).mockResolvedValue({ items: [{ packageId: 'payments', name: 'Payments' }] });
    vi.spyOn(api, 'getDeclaredConfiguration').mockResolvedValue({ environments: [{ name: 'UAT', state: 'active' }] });
    vi.spyOn(api, 'getPackageManifest').mockResolvedValue({
      packageId: 'payments', present: true, canEdit: false, manifestDigest: 'a'.repeat(64),
      environment: 'UAT', environmentOrigin: 'request',
    });

    await act(async () => root.render(<App />));
    await settle();

    const runForm = host.querySelector<HTMLElement>('.att-run-definition')!;
    expect(runForm.querySelector<HTMLInputElement>('input')?.value).toBe('payments');
    const environmentLabel = [...runForm.querySelectorAll('label')].find(label => label.textContent?.startsWith('Environment'))!;
    expect(environmentLabel.querySelector<HTMLSelectElement>('select')?.value).toBe('UAT');
  });

  it('keeps Workspace context when navigating to Configuration and returning with Back', async () => {
    sessionStorage.setItem('att.workspace.context', JSON.stringify({ packageId: 'payments', environment: 'UAT' }));
    window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/');
    vi.mocked(api.getPackages).mockResolvedValue({ items: [{ packageId: 'payments', name: 'Payments' }] });
    vi.spyOn(api, 'getDeclaredConfiguration').mockResolvedValue({ environments: [{ name: 'UAT', state: 'active' }] });
    vi.spyOn(api, 'getPackageManifest').mockResolvedValue({
      packageId: 'payments', present: true, canEdit: false, manifestDigest: 'a'.repeat(64),
      environment: 'UAT', environmentOrigin: 'request',
    });
    vi.spyOn(api, 'listConfigurationFiles').mockResolvedValue({ items: [], truncated: false, nextOffset: null });

    await act(async () => root.render(<App />));
    await settle();
    const configurationLink = host.querySelector<HTMLAnchorElement>('[data-nav="configuration"]')!;
    expect(configurationLink.getAttribute('href')).toBe('#/configuration/payments?environment=UAT');
    await act(async () => {
      configurationLink.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
      await vi.waitFor(() => expect(window.location.hash).toBe('#/configuration/payments?environment=UAT'));
    });
    expect(parseRoute(window.location.hash)).toMatchObject({ kind: 'configuration', packageId: 'payments', environment: 'UAT' });

    await act(async () => {
      window.history.back();
      await vi.waitFor(() => expect(window.location.hash).toBe('#/'));
    });
    await settle();
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Workspace Package"]')?.value).toBe('payments');
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Workspace Environment"]')?.value).toBe('UAT');
  });

  it('redirects a legacy Testdata link into the Package route without changing Workspace context', async () => {
    sessionStorage.setItem('att.workspace.context', JSON.stringify({ packageId: 'payments', environment: 'SIT' }));
    window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/configuration/payments?tab=testdata&environment=UAT&descriptorId=accounts');
    vi.mocked(api.getPackages).mockResolvedValue({ items: [{ packageId: 'payments', name: 'Payments' }] });
    vi.spyOn(api, 'getDeclaredConfiguration').mockResolvedValue({ environments: [
      { name: 'SIT', state: 'active' }, { name: 'UAT', state: 'active' },
    ] });
    vi.spyOn(api, 'getEffectiveConfiguration').mockImplementation(async (_packageId, query) => query.section === 'testdata'
      ? { environment: 'UAT', total: 1, sections: [{ id: 'testdata', entries: [{ id: 'accounts', source: 'testdata/accounts.yaml' }] }] }
      : { environment: 'SIT', sections: [] });
    vi.spyOn(api, 'getTestdataRecords').mockResolvedValue({ descriptorId: 'accounts', offset: 0, limit: 50, total: 1,
      records: [{ index: 0, value: { id: 'A-1' } }] });

    await act(async () => root.render(<App />)); await settle();
    expect(window.location.hash).toBe('#/packages/payments/testdata?environment=UAT&descriptor=accounts');
    expect(host.textContent).toContain('Package: Payments');
    expect(host.textContent).toContain('accounts');
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Workspace Environment"]')?.value).toBe('SIT');
    expect(JSON.parse(sessionStorage.getItem('att.workspace.context')!)).toEqual({ packageId: 'payments', environment: 'SIT' });
    expect(api.getEffectiveConfiguration).toHaveBeenCalledWith('payments', expect.objectContaining({ section: 'testdata', environment: 'UAT' }), expect.any(AbortSignal));
  });

  it('inspects another Package Testdata without changing Workspace Package or Environment', async () => {
    sessionStorage.setItem('att.workspace.context', JSON.stringify({ packageId: 'payments', environment: 'UAT' }));
    window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/packages/ledger/testdata?environment=QA&descriptor=accounts');
    vi.mocked(api.getPackages).mockResolvedValue({ items: [
      { packageId: 'payments', name: 'Payments' }, { packageId: 'ledger', name: 'Ledger' },
    ] });
    vi.spyOn(api, 'getDeclaredConfiguration').mockImplementation(async packageId => ({ environments: packageId === 'ledger'
      ? [{ name: 'QA', state: 'active', default: true }]
      : [{ name: 'UAT', state: 'active' }] }));
    vi.spyOn(api, 'getEffectiveConfiguration').mockImplementation(async (packageId, query) => ({
      environment: query.environment || (packageId === 'ledger' ? 'QA' : 'UAT'), total: 1,
      sections: [{ id: 'testdata', entries: [{ id: 'accounts', source: 'testdata/accounts.yaml' }] }],
    }));
    vi.spyOn(api, 'getTestdataRecords').mockResolvedValue({ descriptorId: 'accounts', offset: 0, limit: 50, total: 0, records: [] });

    await act(async () => root.render(<App />)); await settle();
    expect(host.textContent).toContain('Package: Ledger');
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Testdata environment"]')?.value).toBe('QA');
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Workspace Package"]')?.value).toBe('payments');
    expect(host.querySelector<HTMLSelectElement>('[aria-label="Workspace Environment"]')?.value).toBe('UAT');
    expect(JSON.parse(sessionStorage.getItem('att.workspace.context')!)).toEqual({ packageId: 'payments', environment: 'UAT' });
    expect(window.location.hash).toBe('#/packages/ledger/testdata?environment=QA&descriptor=accounts');
  });

  it('refreshes after returning to Dashboard and shows newly completed Debug and Load rows', async () => {
    const updatedRows: JobSummary[] = [
      { jobId: 'J-LOAD', command: 'load', packageId: 'payments', status: 'ERROR', createdAt: '2026-10-15T10:00:00Z' },
      { jobId: 'J-DEBUG', command: 'run', packageId: 'payments', status: 'FAIL', createdAt: '2026-10-15T09:00:00Z' },
    ];
    const getJobs = vi.spyOn(api, 'getJobs')
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: updatedRows });

    await act(async () => root.render(<App />));
    await settle();
    expect(host.textContent).toContain('No jobs yet');
    expect(getJobs).toHaveBeenCalledTimes(1);

    clickNavigation('jobs');
    await settle();
    clickNavigation('dashboard');
    await settle();

    expect(getJobs).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('J-LOAD');
    expect(host.textContent).toContain('J-DEBUG');
    const cards = [...host.querySelectorAll<HTMLElement>('.summary-card')];
    const card = (label: string) => cards.find(item => item.querySelector('.summary-label')?.textContent === label);
    expect(card('Recent jobs')?.querySelector('.summary-value')?.textContent).toBe('2');
    const outcomes = [...(card('Recent outcomes')?.querySelectorAll<HTMLElement>('.summary-outcome-count') || [])];
    expect(outcomes.map(item => [item.querySelector('span')?.textContent, item.querySelector('strong')?.textContent]))
      .toEqual([['PASS', '0'], ['FAIL', '1'], ['ERROR', '1']]);
    expect(host.textContent).not.toContain('No jobs yet');
  });

  it('polls while a job is active and updates Dashboard when it reaches a terminal status', async () => {
    const running: JobSummary = {
      jobId: 'J-LOAD', command: 'load', packageId: 'payments', status: 'RUNNING', createdAt: '2026-10-15T10:00:00Z',
    };
    const passed: JobSummary = { ...running, status: 'PASS', finishedAt: '2026-10-15T10:00:05Z' };
    const getJobs = vi.spyOn(api, 'getJobs')
      .mockResolvedValueOnce({ items: [running] })
      .mockResolvedValueOnce({ items: [passed] });
    vi.useFakeTimers();

    await act(async () => root.render(<App />));
    await settle();
    expect(host.querySelector('[aria-label="Dashboard summary"]')?.textContent).toContain('RUNNING');
    expect(getJobs).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    await settle();

    expect(getJobs).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[aria-label="Dashboard summary"]')?.textContent).toContain('PASS');
    expect(host.querySelector('[aria-label="Dashboard summary"]')?.textContent).toContain('1');
  });

  it('opens a bookmarkable standalone artifact route and follows report links with browser history', async () => {
    await import('./features/load/StandaloneJobArtifactPage');
    const initialHash = '#/jobs/JOB-ARTIFACT/artifact?path=run%2Freport%2Findex.html';
    window.history.replaceState(null, '', window.location.pathname + window.location.search + initialHash);
    vi.spyOn(api, 'getJob').mockResolvedValue({ jobId: 'JOB-ARTIFACT', command: 'run', packageId: 'demo', status: 'PASS' });
    vi.spyOn(artifactApi, 'getJobArtifactArchive').mockResolvedValue({ archiveStatus: 'READY', items: [], primaryReports: [] });
    vi.spyOn(artifactApi, 'getJobArtifactText').mockImplementation(async (_jobId, path) =>
      path.endsWith('.html')
        ? '<html><head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'sha256-reviewed\'"></head><body><h1>Approved report</h1><a href="../case.log">Case log</a></body></html>'
        : 'approved linked log');

    await act(async () => root.render(<App />));
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)); });
    await act(async () => { await vi.waitFor(() => expect(host.querySelector('iframe')).not.toBeNull()); });
    expect(parseRoute(window.location.hash)).toEqual({ kind: 'job-artifact', jobId: 'JOB-ARTIFACT', path: 'run/report/index.html' });
    expect(host.querySelector('.app-sidebar')).toBeNull();
    expect(host.querySelector('#main-content')?.classList.contains('att-standalone-console-shell')).toBe(true);
    const firstFrame = host.querySelector('iframe')!;
    expect(firstFrame.getAttribute('sandbox')).toBe('allow-scripts');

    await act(async () => {
      window.dispatchEvent(new MessageEvent('message', {
        source: firstFrame.contentWindow, data: { type: 'att-report-navigation', href: '../case.log' },
      }));
    });
    const linkedHash = '#/jobs/JOB-ARTIFACT/artifact?path=run%2Fcase.log';
    await act(async () => { await vi.waitFor(() => expect(window.location.hash).toBe(linkedHash)); });
    await act(async () => { await vi.waitFor(() => expect(host.textContent).toContain('approved linked log')); });

    await act(async () => { window.history.back(); await vi.waitFor(() => expect(window.location.hash).toBe(initialHash)); });
    await act(async () => { await vi.waitFor(() => expect(host.querySelector('iframe')).not.toBeNull()); });
    await act(async () => { window.history.forward(); await vi.waitFor(() => expect(window.location.hash).toBe(linkedHash)); });
    expect(parseRoute(window.location.hash)).toEqual({ kind: 'job-artifact', jobId: 'JOB-ARTIFACT', path: 'run/case.log' });
  });

  it('keeps a dirty Quick Load environment switch transactional across Cancel and Confirm', async () => {
    window.history.replaceState(null, '', window.location.pathname + window.location.search
      + '#/quick-load/payments/template/checkout?environment=SIT');
    vi.mocked(api.getVersion).mockResolvedValue({ apiVersion: '1', inlineLoadEnabled: true });
    vi.spyOn(api, 'getDeclaredConfiguration').mockResolvedValue({ environments: [
      { name: 'SIT', state: 'active' }, { name: 'UAT', state: 'active' },
    ] });
    vi.spyOn(api, 'getLoadPolicy').mockImplementation(async (_packageId, _model, environment) => ({
      model: 'virtualUsers',
      policy: { schemaVersion: 'att-load/v1.6', load: environment === 'UAT' ? { users: 9, duration: '90s' } : { users: 2, duration: '30s' } },
    }));
    vi.spyOn(api, 'getTargetLoadForm').mockImplementation(async (_packageId, type, resourceId, _model, environment) => ({
      target: { type, id: resourceId }, model: 'virtualUsers',
      input: { inputs: { region: environment === 'UAT' ? 'uat-region' : 'sit-region' }, vars: {} },
    }));
    vi.spyOn(api, 'getJobs').mockResolvedValue({ items: [] });

    await act(async () => root.render(<App />));
    await settle();
    const durationInput = () => host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]');
    await act(async () => { await vi.waitFor(() => expect(durationInput()?.value).toBe('30s'), { timeout: 5000 }); });
    expect(durationInput()?.value).toBe('30s');
    const environmentText = () => host.querySelector<HTMLSelectElement>('select[aria-label="Workspace Environment"]')?.value;
    const initialSharedEnvironment = environmentText();

    const duration = durationInput()!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(duration, '5m');
      duration.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const environment = () => [...host.querySelectorAll('label')].find(label => label.textContent?.startsWith('Environment'))!.querySelector('select') as HTMLSelectElement;
    await act(async () => {
      environment().value = 'UAT';
      environment().dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(host.querySelector('.discard-dialog[open]')).not.toBeNull();
    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Stay on this page')!.click());
    await settle();
    expect(window.location.hash).toContain('environment=SIT');
    expect(environment().value).toBe('SIT');
    expect(environmentText()).toBe(initialSharedEnvironment);
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')?.value).toBe('5m');

    await act(async () => {
      environment().value = 'UAT';
      environment().dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(host.querySelector('.discard-dialog[open]')).not.toBeNull();
    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Discard changes and continue')!.click());
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)); });
    await settle();

    expect(window.location.hash).toContain('environment=UAT');
    expect(environmentText()).toBe(initialSharedEnvironment);
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')?.value).toBe('90s');
    expect(host.querySelector<HTMLInputElement>('input[type="number"]')?.value).toBe('9');
    expect(host.querySelector<HTMLInputElement>('input[aria-label="region value"]')?.value).toBe('uat-region');

    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Back to Explorer')!.click());
    expect(host.querySelector('.discard-dialog[open]')).toBeNull();
    expect(window.location.hash).not.toContain('quick-load');
  });
});
