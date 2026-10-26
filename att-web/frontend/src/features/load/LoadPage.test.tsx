// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoadPage, retainedScenarioToEditor } from './LoadPage';
import type { LoadJobsApi } from './types';
import { NavigationGuardContext } from '../../router';

function makeApi(): LoadJobsApi {
  return {
    getLoadPolicy: vi.fn(async () => ({
      model: 'virtualUsers' as const,
      policy: { schemaVersion: 'att-load/v1.6', load: { warmup: '5s', rampUp: '10s', duration: '30s', rampDown: '5s' } },
      redacted: true,
    })),
    getTargetLoadForm: vi.fn(async () => ({
      model: 'virtualUsers' as const, target: { type: 'template' as const, id: 'Payments.create' },
      input: { inputs: { accountId: 'acct-1', retry: 2 }, vars: { token: { $attDebugKeepDefault: 'hidden' } } }, redacted: true,
    })),
    listTargets: vi.fn(async () => [{ type: 'template' as const, resourceId: 'opaque-payments-create', logicalId: 'Payments.create', state: 'ready' }]),
    createLoadDraft: vi.fn(async () => ({
      draftId: 'A0123456789ABCDEF0123456789ABCDEF', packageId: 'payments', model: 'virtualUsers' as const,
      previewYaml: 'schemaVersion: att-load/v1.6', expiresAt: new Date(Date.now() + 60_000).toISOString(), redacted: true,
    })),
    submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-LOAD', status: 'QUEUED' })),
    listJobs: vi.fn(async () => []), getJob: vi.fn(), getJobResult: vi.fn(), getJobArtifacts: vi.fn(), cancelJob: vi.fn(),
    subscribeJob: vi.fn(() => () => {}), artifactHref: vi.fn((_jobId, path) => path),
  };
}

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

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
}

async function click(element: Element) {
  await act(async () => { element.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
}

describe('LoadPage React feature', () => {
  it('inherits retained root pacing defaults and leaves unavailable workload values empty', () => {
    const retained=retainedScenarioToEditor({schemaVersion:'att-load/v1.9',load:{warmup:'2s',duration:'24h',users:12},workloads:[{id:'root-defaults',target:{type:'template',id:'checkout'}}]});
    expect(retained.model).toBe('virtualUsers');expect(retained.timing.duration).toBe('24h');
    expect(retained.workloads[0].id).toBe('root-defaults');expect(retained.workloads[0].users).toBe('12');
    expect(retainedScenarioToEditor({schemaVersion:'att-load/v1.9',load:{duration:'30m'},workloads:[{id:'unavailable',target:{type:'template',id:'checkout'}}]}).workloads[0].users).toBe('');
    const arrival=retainedScenarioToEditor({schemaVersion:'att-load/v1.9',load:{duration:'24h',arrivalRate:'3/s',maxConcurrent:6,overloadPolicy:'drop'},workloads:[{id:'arrival-root',target:{type:'template',id:'checkout'}}]});
    expect(arrival.model).toBe('arrivalRate');expect(arrival.workloads[0].arrivalRate).toBe('3/s');expect(arrival.workloads[0].maxConcurrent).toBe('6');
  });

  it('prefills a package-backed LOAD draft, preserves workload identity and validates edited timing', async () => {
    const api=makeApi(),confirmStart=vi.fn(()=>true),onJobAccepted=vi.fn();
    api.getJobReplayEditorDraft=vi.fn().mockResolvedValue({schemaVersion:'att-job-replay-draft/v1',draftRef:'C0123456789ABCDEF0123456789ABCDEF',sourceJobId:'J0123456789ABCDEF',packageId:'payments',executionType:'load',sourcePolicy:'current',
      originalRevision:'a'.repeat(64),currentRevision:'b'.repeat(64),configuration:{packageId:'payments',environment:'SIT'},expiresAt:new Date(Date.now()+60000).toISOString(),missingFields:[],
      definition:{executionType:'load',packageId:'payments',environment:'SIT',sourcePath:'load/nightly.yaml',scenario:{schemaVersion:'att-load/v1.9',load:{warmup:'2s',rampUp:'3s',duration:'30m',rampDown:'4s'},workloads:[
        {id:'checkout-main',target:{type:'template',id:'Payments.create'},inputs:{region:'HK'},load:{users:4},thresholds:{p95:'< 800ms'}},
        {id:'checkout-mix',mix:[{id:'purchase',weight:100,target:{type:'flow',id:'Payments.purchase'}}],load:{users:2}},
      ],thresholds:{minThroughput:'>= 10/s'},evidence:{mode:'failures',maxFailureSamples:5},testdata:['examples/accounts.yaml']}}});
    api.createJobReplayDraft=vi.fn().mockResolvedValue({jobId:'J0123456789ABCDEF',available:true,sourcePolicy:'current',intentId:'intent',draftId:'C123',originalRevision:'a'.repeat(64),currentRevision:'c'.repeat(64),
      preview:{draftId:'C123',previewYaml:'schemaVersion: att-load/v1.9',expiresAt:new Date(Date.now()+60000).toISOString(),redacted:false,diagnostics:[]}});
    api.rerunJob=vi.fn().mockResolvedValue({jobId:'J-new'});
    const navigate=vi.fn();
    await act(async()=>root.render(<NavigationGuardContext.Provider value={{navigate,registerUnsavedChanges:vi.fn(()=>vi.fn())} as never}><LoadPage
      api={api} packages={[{packageId:'payments',displayName:'Payments'}]} selectedPackage="payments" selectedEnvironment="SIT" environments={['SIT']} inlineLoadEnabled={false}
      replayDraftId="C0123456789ABCDEF0123456789ABCDEF" replaySourceJobId="J0123456789ABCDEF" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={onJobAccepted} confirmStart={confirmStart}
    /></NavigationGuardContext.Provider>));
    await flush();
    expect(api.getJobReplayEditorDraft).toHaveBeenCalledWith('J0123456789ABCDEF','C0123456789ABCDEF0123456789ABCDEF');
    expect((host.querySelector('[aria-label="Measured duration value"]') as HTMLInputElement).value).toBe('30');
    expect((host.querySelector('[aria-label="Measured duration unit"]') as HTMLSelectElement).value).toBe('m');
    expect(([...host.querySelectorAll('label')].find(label=>label.textContent?.startsWith('Workload ID'))?.querySelector('input') as HTMLInputElement).value).toBe('checkout-main');
    const model=[...host.querySelectorAll('label')].find(label=>label.textContent?.includes('Load model'))?.querySelector('select') as HTMLSelectElement;
    expect(model.disabled).toBe(true);
    const amount=host.querySelector('[aria-label="Measured duration value"]') as HTMLInputElement;
    await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(amount,'24');amount.dispatchEvent(new Event('input',{bubbles:true}));});
    const unit=host.querySelector('[aria-label="Measured duration unit"]') as HTMLSelectElement;
    await act(async()=>{unit.value='h';unit.dispatchEvent(new Event('change',{bubbles:true}));});
    expect(host.textContent).not.toContain('Effective duration:');
    await click([...host.querySelectorAll('button')].find(button=>button.textContent==='Validate scenario')!);await flush();
    expect(api.createJobReplayDraft).toHaveBeenCalledWith('J0123456789ABCDEF',expect.objectContaining({sourcePolicy:'current',overrides:expect.objectContaining({
      environment:'SIT',load:expect.objectContaining({duration:'24h',warmup:'2s',rampUp:'3s',rampDown:'4s'}),
      workloads:expect.arrayContaining([expect.objectContaining({id:'checkout-main',load:expect.objectContaining({users:4})}),expect.objectContaining({id:'checkout-mix',load:expect.objectContaining({users:2}),mix:expect.anything()})]),
      testdata:['examples/accounts.yaml'],evidence:{mode:'failures',maxFailureSamples:5},thresholds:{minThroughput:'>= 10/s'},
    })}));
    expect(host.textContent).toContain('Effective duration:');expect(host.textContent).toContain('Review changes from the source Job');
    expect(host.textContent).toContain('Configured target arrival rate is the pacing request');
  });

  it('uses the keyboard submit shortcut only for a live draft and preserves confirmation', async () => {
    const api = makeApi();
    const confirmStart = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true);
    await act(async () => root.render(<NavigationGuardContext.Provider value={{navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>vi.fn())} as never}><LoadPage
      api={api}
      packages={[{ packageId: 'payments', displayName: 'Payments' }]}
      selectedPackage="payments"
      selectedEnvironment=""
      environments={['SIT']}
      inlineLoadEnabled
      onPackageChange={vi.fn()}
      onEnvironmentChange={vi.fn()}
      onJobAccepted={vi.fn()}
      confirmStart={confirmStart}
    /></NavigationGuardContext.Provider>));
    await flush();
    const targetInput = host.querySelector('.att-target-editor input[list]') as HTMLInputElement;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(targetInput, 'Payments.create');
      targetInput.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await flush();
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Validate scenario')!);
    await flush();
    expect(api.createLoadDraft).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('Server validation passed');

    await act(async () => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, bubbles: true })));
    await flush();
    expect(confirmStart).toHaveBeenCalledTimes(1);
    expect(api.submitLoadDraft).not.toHaveBeenCalled();

    await act(async () => document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', metaKey: true, bubbles: true })));
    await flush();
    expect(confirmStart).toHaveBeenCalledTimes(2);
    expect(api.submitLoadDraft).toHaveBeenCalledTimes(1);
  });

  it('uses section navigation, typed workload controls, and typed sidecar fields before Server validation', async () => {
    const api = makeApi();
    const onJobAccepted = vi.fn();
    const confirmStart = vi.fn(() => true);
    await act(async () => root.render(<NavigationGuardContext.Provider value={{navigate:vi.fn(),registerUnsavedChanges:vi.fn(()=>vi.fn())} as never}><LoadPage
      api={api}
      packages={[{ packageId: 'payments', displayName: 'Payments' }]}
      selectedPackage="payments"
      selectedEnvironment=""
      environments={['SIT']}
      inlineLoadEnabled
      onPackageChange={vi.fn()}
      onEnvironmentChange={vi.fn()}
      onJobAccepted={onJobAccepted}
      confirmStart={confirmStart}
    /></NavigationGuardContext.Provider>));
    await flush();

    expect(host.querySelectorAll('.att-section-nav button')).toHaveLength(4);
    expect(host.querySelector('.att-section-nav [aria-controls="load-workloads"]')).not.toBeNull();
    const measured = host.querySelector('input[aria-label="Measured duration value"]') as HTMLInputElement;
    expect(measured.value).toBe('30');
    await act(async () => {
      host.querySelector<HTMLButtonElement>('.att-section-nav [aria-controls="load-workloads"]')?.click();
    });
    expect(document.activeElement?.id).toBe('load-workloads');
    expect(window.location.hash).not.toContain('load-workloads');

    const model = [...host.querySelectorAll('label')].find(label => label.textContent?.includes('Load model'))?.querySelector('select');
    expect(model).not.toBeNull();
    await act(async () => {
      model!.value = 'arrivalRate';
      model!.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    expect(host.querySelector('input[aria-label="Arrival rate value"]')).not.toBeNull();
    expect(host.querySelector('.att-think-time')?.hasAttribute('disabled')).toBe(true);
    expect(host.textContent).not.toContain('Weighted mix (Virtual Users only)');

    await act(async () => {
      model!.value = 'virtualUsers';
      model!.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    const idInput = host.querySelector('.att-target-editor input[list]') as HTMLInputElement;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(idInput, 'Payments.create');
      idInput.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await flush();
    const sidecar = [...host.querySelectorAll('button')].find(button => button.textContent === 'Load sidecar defaults');
    expect(sidecar).toBeDefined();
    await click(sidecar!);
    await flush();
    expect(api.getTargetLoadForm).toHaveBeenCalledWith('payments', 'template', 'opaque-payments-create', 'virtualUsers', undefined, expect.any(AbortSignal));
    expect(host.querySelector('input[aria-label="accountId value"]')).not.toBeNull();
    expect(host.textContent).toContain('Keep hidden package default');
    expect((host.querySelector('input[aria-label="accountId value"]') as HTMLInputElement).value).toBe('acct-1');

    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate scenario');
    expect(validate).toBeDefined();
    await click(validate!);
    await flush();
    expect(api.createLoadDraft).toHaveBeenCalledTimes(1);
    const body = vi.mocked(api.createLoadDraft).mock.calls[0][0];
    const scenario = body.scenario as { workloads: Array<{ target: { id: string }; inputs: { accountId: string }; vars: Record<string, unknown> }> };
    expect(scenario.workloads[0].target.id).toBe('Payments.create');
    expect(scenario.workloads[0].inputs.accountId).toBe('acct-1');
    expect(scenario.workloads[0].vars.token).toEqual({ $attDebugKeepDefault: 'hidden' });

    const start = [...host.querySelectorAll('button')].find(button => button.textContent === 'Review and start Load');
    expect(start).toBeDefined();
    await act(async () => {
      start!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      start!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    await flush();
    expect(confirmStart).toHaveBeenCalledTimes(1);
    expect(api.submitLoadDraft).toHaveBeenCalledTimes(1);
    expect(onJobAccepted).toHaveBeenCalledWith('JOB-LOAD');
  });
});
