import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('../../main/resources/META-INF/resources/ui/app.js', import.meta.url), 'utf8');
async function waitFor(predicate, description, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${description}`);
    await new Promise(resolve => setImmediate(resolve));
  }
}
function allText(node) { return [node.textContent || '', ...(node.children || []).map(allText)].join(' '); }

function boot({ hash = '', version = '1', confirmCancel = true, jobMissing = false, deferHome = false, deferSubmit = false, deferCancel = false, postForbidden = false, deferArtifacts = false, deferJobLists = false, deferResult = false, deferConfiguration = false, configuration = {}, jobStatus = 'RUNNING', artifactItems = [], resourceItems = [], resourcePages = [], debugFormResponse = null, debugDraftResponses = [], debugSubmitErrors = [], quickLoadFormResponse = null, quickLoadDraftResponses = [] } = {}) {
  class Element {
    constructor() {
      this.children = []; this.listeners = {}; this.elements = {}; this.dataset = {};
      this.textContent = ''; this.hidden = false; this.disabled = false; this.submitButton = { disabled: false };
    }
    addEventListener(name, listener) { this.listeners[name] = listener; }
    append(...nodes) { for (const node of nodes) { node.parent = this; this.children.push(node); } }
    replaceChildren(...nodes) { this.children = []; this.append(...nodes); }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this); }
    get firstElementChild() { return this.children[0]; }
    querySelector(selector) { return selector === 'button[type="submit"]' ? this.submitButton : null; }
    reset() { this.formValues = []; }
  }
  const elements = new Map();
  const node = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  node('submit-form').elements.environment = Object.assign(new Element(), { value: '' });
  const document = {
    baseURI: 'https://example.test/tools/att/ui/',
    getElementById: node,
    createElement: () => new Element()
  };
  const location = { hash };
  const window = { listeners: {}, confirm: () => confirmCancel,
    addEventListener(name, listener) { this.listeners[name] = listener; }
  };
  const calls = [], streams = [], pendingSubmissions = [], pendingCancellations = [], pendingArtifacts = [], pendingHomeJobs = [], pendingResults = [], pendingConfigurations = [];
  const state = { jobStatus, resourcePage: 0, debugDraft: 0, debugSubmit: 0, quickLoadDraft: 0 };
  let rejectHome;
  const delayedHome = new Promise((_, reject) => { rejectHome = reject; });
  class EventSource {
    constructor(url) { this.url = url; this.listeners = {}; this.closed = false; streams.push(this); }
    addEventListener(type, listener) { this.listeners[type] = listener; }
    close() { this.closed = true; }
    emit(type, data, id) {
      if (this.listeners[type]) this.listeners[type]({ data: JSON.stringify(data), lastEventId: String(id) });
    }
  }
  async function fetch(url, options = {}) {
    const path = String(url).split('/api/v1/')[1];
    calls.push({ path, options });
    const jsonResponse = data => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => data });
    if (deferSubmit && options.method === 'POST') return new Promise((resolve, reject) => pendingSubmissions.push({ resolve, reject }));
    if (deferCancel && options.method === 'DELETE') return new Promise((resolve, reject) => pendingCancellations.push({ resolve, reject }));
    if (deferArtifacts && /^jobs\/[^/]+\/artifacts$/.test(path)) return new Promise(resolve => pendingArtifacts.push(resolve));
    if (deferJobLists && path === 'jobs' && !options.method) return new Promise((resolve, reject) => pendingHomeJobs.push({ resolve, reject }));
    if (deferResult && /^jobs\/[^/]+\/result$/.test(path)) return new Promise(resolve => pendingResults.push(resolve));
    if (deferConfiguration && /^packages\/[^/]+\/configuration(?:\/|\?)/.test(path)) return new Promise(resolve => pendingConfigurations.push(resolve));
    if (deferHome && (path === 'packages' || path === 'jobs')) return delayedHome;
    if (postForbidden && path === 'jobs/run' && options.method === 'POST') {
      return { ok: false, status: 403, headers: { get: () => 'application/json' },
        json: async () => ({ error: { code: 'ATT-SERVER-CROSS-ORIGIN-REQUEST', summary: 'State-changing requests must use the same origin.' } }) };
    }
    if (jobMissing && path === 'jobs/nonexistent') {
      return { ok: false, status: 404, headers: { get: () => 'application/json' },
        json: async () => ({ error: { summary: 'Job not found' } }) };
    }
    if (path === 'jobs/debug' && options.method === 'POST') {
      const failure = debugSubmitErrors[state.debugSubmit++];
      if (failure) return { ok: false, status: failure.status, headers: { get: () => 'application/json' },
        json: async () => ({ error: { code: failure.code || 'ATT-SERVER-DRAFT-STALE', summary: failure.summary || 'Debug draft is no longer available' } }) };
      return jsonResponse({ jobId: 'J_DEBUG' });
    }
    let result;
    if (path === 'version') result = { apiVersion: version };
    else if (path === 'packages') result = { items: [{ packageId: 'payments' }] };
    else if (/^packages\/[^/]+\/resources\?/.test(path)) result = resourcePages.length
      ? resourcePages[Math.min(state.resourcePage++, resourcePages.length - 1)]
      : { items: resourceItems, total: resourceItems.length, nextCursor: null };
    else if (/^packages\/[^/]+\/resources\/[^/]+\/[^/]+\/debug-form(?:\?|$)/.test(path)) result = debugFormResponse || {
      input: { schemaVersion: 'att-debug/v1.2', inputs: { payload: { $attDebugKeepDefault: '/inputs/payload' } } },
      target: { type: 'template', id: 'TEST' }, redacted: true
    };
    else if (/^packages\/[^/]+\/resources\/[^/]+\/[^/]+\/quick-load-form\?/.test(path)) result = quickLoadFormResponse || {
      input: { inputs: {}, vars: {} }, target: { type: 'template', id: 'TEST' }, model: 'virtualUsers',
      preview: { load: { users: 1, duration: '10s' }, workloads: [{ execution: {} }] },
      loadTestdata: [], debugLocalTestdataOmitted: false, redacted: false
    };
    else if (path === 'drafts/debug' && options.method === 'POST') {
      const index = state.debugDraft++;
      result = debugDraftResponses[Math.min(index, debugDraftResponses.length - 1)] || {
        draftId: `D${index + 1}`, preview: {}, previewYaml: 'schemaVersion: att-debug/v1.2',
        redacted: true, expiresAt: new Date(Date.now() + 60000).toISOString()
      };
    }
    else if (path === 'drafts/quick-load' && options.method === 'POST') {
      const index = state.quickLoadDraft++;
      result = quickLoadDraftResponses[Math.min(index, quickLoadDraftResponses.length - 1)] || {
        draftId: `L${index + 1}`, target: { type: 'template', id: 'TEST' }, model: 'virtualUsers', preview: {}, previewYaml: 'schemaVersion: att-load/v1.6',
        redacted: false, expiresAt: new Date(Date.now() + 60000).toISOString()
      };
    }
    else if (/^packages\/[^/]+\/resources\/[^/]+\/[^/]+\/source$/.test(path)) result = { available: true, text: '<img src=x onerror=alert(1)>', format: 'yaml' };
    else if (/^packages\/[^/]+\/resources\/[^/]+\/[^/]+$/.test(path)) result = { resource: resourceItems[0], definition: { action: 'log' }, diagnostics: [] };
    else if (/^packages\/[^/]+\/configuration\/effective\?/.test(path)) result = configuration.effective || {};
    else if (/^packages\/[^/]+\/configuration\/compare\?/.test(path)) result = configuration.compare || {};
    else if (/^packages\/[^/]+\/configuration\?/.test(path)) result = configuration.declared || {};
    else if (path.startsWith('packages/')) result = { packageId: path.substring(9) };
    else if (path === 'jobs') result = { items: [] };
    else if (/^jobs\/(run|debug|load|validate)$/.test(path) && options.method === 'POST') result = { jobId: 'J1' };
    else if (/^jobs\/[^/]+\/artifacts$/.test(path)) result = { items: artifactItems };
    else if (/^jobs\/[^/]+\/result$/.test(path)) result = { result: { passed: 1 }, diagnostic: null };
    else if (/^jobs\/[^/]+$/.test(path) && options.method === 'DELETE') result = { status: 'CANCEL_REQUESTED' };
    else if (/^jobs\/[^/]+$/.test(path)) result = { jobId: path.split('/')[1], status: state.jobStatus, packageId: 'payments', command: 'run' };
    else throw Error('Unexpected fetch path ' + path);
    return jsonResponse(result);
  }
  class FormData {
    constructor(form) { return new Map(form.formValues || []); }
  }
  vm.runInNewContext(script, { document, location, window, fetch, EventSource, URL, Headers, FormData, console }, { filename: 'app.js' });
  return { node, location, window, calls, streams, state, pendingSubmissions, pendingCancellations, pendingArtifacts, pendingHomeJobs, pendingResults,
    resolveSubmission: (index, response) => pendingSubmissions[index].resolve(response),
    rejectSubmission: (index, error) => pendingSubmissions[index].reject(error),
    resolveCancellation: (index, response) => pendingCancellations[index].resolve(response),
    resolveArtifacts: (index, response) => pendingArtifacts[index](response),
    resolveConfiguration: (index, data) => pendingConfigurations[index](data && data.ok !== undefined ? data : ({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => data })),
    resolveHomeJobList: (index, response) => pendingHomeJobs[index].resolve(response),
    resolveResult: (index, response) => pendingResults[index](response),
    rejectCancellation: (index, error) => pendingCancellations[index].reject(error),
    failHome: error => rejectHome(error) };
}

async function openDebugForm(ui, resource) {
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 1'), 'package resource index');
  await ui.node('resource-list').children[0].children[0].listeners.click();
  await ui.node('debug-resource').listeners.click();
  await waitFor(() => ui.node('debug-form-editor').hidden === false, 'Debug form');
}

async function openQuickLoadForm(ui) {
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 1'), 'package resource index');
  await ui.node('resource-list').children[0].children[0].listeners.click();
  await ui.node('quick-load-resource').listeners.click();
  await waitFor(() => ui.node('quick-load-form-editor').hidden === false, 'Quick Load form');
}

test('rejects an incompatible API before accessing packages', async () => {
  const ui = boot({ version: '2' });
  await waitFor(() => ui.node('message').textContent.includes('Incompatible ATT Server API version'), 'API version rejection');
  assert.match(ui.node('message').textContent, /Incompatible ATT Server API version/);
  assert.deepEqual(ui.calls.map(call => call.path), ['version']);
});

test('submits logical package DTOs with a non-root Tomcat context', async () => {
  const ui = boot({ hash: '#/packages/payments' });
  await waitFor(() => ui.node('package-title').textContent === 'payments', 'package form');
  const form = ui.node('submit-form');
  form.formValues = [['command', 'run'], ['environment', 'SIT'], ['tags', 'smoke, regression'], ['all', 'on']];
  await form.listeners.submit({ preventDefault() {}, currentTarget: form });
  const posted = ui.calls.find(call => call.path === 'jobs/run');
  assert.ok(posted);
  assert.deepEqual(JSON.parse(posted.options.body), {
    packageId: 'payments', environment: 'SIT', tags: ['smoke', 'regression'], all: true
  });
  assert.equal(ui.location.hash, '#/jobs/J1');
});

test('explores package resources and renders source as text', async () => {
  const resource = { resourceId: 'template.dGVzdA', type: 'template', logicalId: 'TEST', name: 'Test template', description: 'Read only', sourceAvailable: true, state: 'ready', references: [], referencedBy: [] };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [resource] });
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 1'), 'package resource index');
  const row = ui.node('resource-list').children[0];
  await row.children[0].listeners.click();
  assert.equal(ui.node('resource-title').textContent, 'Test template · template');
  assert.equal(ui.node('resource-metadata').textContent, '{\n  "logicalId": "TEST",\n  "tags": [],\n  "provenance": {}\n}');
  assert.equal(ui.node('resource-definition').textContent, '{\n  "action": "log"\n}');
  await ui.node('show-resource-source').listeners.click();
  assert.equal(ui.node('resource-source').textContent, '<img src=x onerror=alert(1)>');
  assert.ok(ui.calls.some(call => call.path.endsWith('/source')));
  assert.equal(ui.node('resource-source').innerHTML, undefined);
});

test('shows invalid and source-unavailable resource states in the detail view', async () => {
  const resource = { resourceId: 'flow.invalid', type: 'flow', logicalId: 'PAYMENT.flow.v1', name: 'Payment flow', description: '', sourceAvailable: false, state: 'invalid', diagnostics: [{ code: 'ATT-RESOURCE-FLOW-INVALID', summary: 'Flow definition is invalid' }] };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [resource] });
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 1'), 'package resource index');
  await ui.node('resource-list').children[0].children[0].listeners.click();
  assert.equal(ui.node('resource-state').textContent, 'Invalid resource: Flow definition is invalid');
  assert.equal(ui.node('resource-source-status').textContent, 'Source is not available for this resource.');
  assert.equal(ui.node('show-resource-source').hidden, true);
});

test('revalidates an expired Debug draft before submission', async () => {
  const resource = { resourceId: 'template.test', type: 'template', logicalId: 'TEST', name: 'Test', state: 'ready', sourceAvailable: false };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [resource], debugDraftResponses: [
    { draftId: 'D_EXPIRED', preview: {}, redacted: true, expiresAt: new Date(Date.now() + 5000).toISOString() },
    { draftId: 'D_FRESH', preview: {}, redacted: true, expiresAt: new Date(Date.now() + 60000).toISOString() }
  ] });
  await openDebugForm(ui, resource);
  await ui.node('preview-debug-form').listeners.click();
  assert.equal(ui.calls.filter(call => call.path === 'drafts/debug').length, 1);
  await ui.node('submit-debug-form').listeners.click();
  await waitFor(() => ui.location.hash === '#/jobs/J_DEBUG', 'Debug job submission');
  assert.equal(ui.calls.filter(call => call.path === 'drafts/debug').length, 2);
  const submitted = ui.calls.filter(call => call.path === 'jobs/debug').map(call => JSON.parse(call.options.body));
  assert.deepEqual(submitted, [{ packageId: 'payments', draftId: 'D_FRESH' }]);
});

test('clears a server-invalidated Debug draft and offers a fresh validation', async () => {
  const resource = { resourceId: 'template.test', type: 'template', logicalId: 'TEST', name: 'Test', state: 'ready', sourceAvailable: false };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [resource], debugSubmitErrors: [{ status: 404 }] });
  await openDebugForm(ui, resource);
  await ui.node('preview-debug-form').listeners.click();
  await ui.node('submit-debug-form').listeners.click();
  await waitFor(() => ui.node('debug-form-status').textContent.includes('Server restarted'), 'stale draft message');
  assert.equal(ui.calls.filter(call => call.path === 'drafts/debug').length, 1);
  await ui.node('submit-debug-form').listeners.click();
  await waitFor(() => ui.location.hash === '#/jobs/J_DEBUG', 'fresh Debug job submission');
  assert.equal(ui.calls.filter(call => call.path === 'drafts/debug').length, 2);
});

test('warns when Debug-local Testdata is omitted and submits separate Load imports', async () => {
  const resource = { resourceId: 'template.form', type: 'template', logicalId: 'FORM', name: 'Form', state: 'ready', sourceAvailable: false };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [resource], quickLoadFormResponse: {
    input: { inputs: { amount: 7 }, vars: { reference: 'REF001' } }, target: { type: 'template', id: 'FORM' }, model: 'virtualUsers',
    preview: { load: { users: 1, duration: '10s' }, workloads: [{ execution: {} }] },
    loadTestdata: [], debugLocalTestdataOmitted: true, redacted: false
  } });
  await openQuickLoadForm(ui);
  assert.match(ui.node('quick-load-form-status').textContent, /Debug-local Testdata was omitted/);
  ui.node('quick-load-testdata').value = '["testdata/load-accounts.yaml"]';
  await ui.node('preview-quick-load-form').listeners.click();
  await waitFor(() => ui.calls.some(call => call.path === 'drafts/quick-load'), 'Quick Load draft validation');
  const submitted = JSON.parse(ui.calls.find(call => call.path === 'drafts/quick-load').options.body);
  assert.deepEqual(submitted.testdata, ['testdata/load-accounts.yaml']);
  assert.deepEqual(submitted.input, { inputs: { amount: 7 }, vars: { reference: 'REF001' } });
  assert.match(ui.node('quick-load-form-status').textContent, /Debug-local Testdata remains omitted/);
});

test('revalidates an expired Quick Load draft before asking to start Load', async () => {
  const resource = { resourceId: 'template.test', type: 'template', logicalId: 'TEST', name: 'Test', state: 'ready', sourceAvailable: false };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [resource], quickLoadDraftResponses: [
    { draftId: 'L_EXPIRED', target: { type: 'template', id: 'TEST' }, model: 'virtualUsers', preview: {}, redacted: false, expiresAt: new Date(Date.now() - 1000).toISOString() },
    { draftId: 'L_FRESH', target: { type: 'template', id: 'TEST' }, model: 'virtualUsers', preview: {}, redacted: false, expiresAt: new Date(Date.now() + 60000).toISOString() }
  ] });
  await openQuickLoadForm(ui);
  await ui.node('preview-quick-load-form').listeners.click();
  await ui.node('submit-quick-load-form').listeners.click();
  assert.equal(ui.calls.filter(call => call.path === 'drafts/quick-load').length, 2, JSON.stringify(ui.calls.map(call => call.path)));
  assert.equal(ui.calls.filter(call => call.path === 'jobs/load').length, 1, ui.node('quick-load-form-status').textContent);
  await waitFor(() => ui.location.hash === '#/jobs/J1', 'Quick Load job submission');
  assert.deepEqual(JSON.parse(ui.calls.find(call => call.path === 'jobs/load').options.body), { packageId: 'payments', draftId: 'L_FRESH' });
});

test('shows safe index diagnostics when some package resources cannot be indexed', async () => {
  const ui = boot({ hash: '#/packages/payments', resourcePages: [{ items: [], total: 0, nextCursor: null, diagnostics: [{ code: 'ATT-RESOURCE-INDEX-WARNING', summary: 'Some resources could not be indexed' }] }] });
  await waitFor(() => ui.node('resource-count').textContent.includes('Some resources could not be indexed'), 'resource index diagnostics');
});

test('loads additional package resource pages with the opaque cursor', async () => {
  const first = { resourceId: 'template.first', type: 'template', name: 'First', sourceAvailable: false, state: 'ready' };
  const second = { resourceId: 'flow.second', type: 'flow', name: 'Second', sourceAvailable: false, state: 'ready' };
  const ui = boot({ hash: '#/packages/payments', resourcePages: [
    { items: [first], total: 2, nextCursor: 'opaque.cursor' },
    { items: [second], total: 2, nextCursor: null }
  ] });
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 2'), 'first resource page');
  assert.equal(ui.node('load-more-resources').hidden, false);
  ui.node('load-more-resources').listeners.click();
  ui.node('load-more-resources').listeners.click();
  await waitFor(() => ui.node('resource-count').textContent.includes('2 of 2'), 'second resource page');
  assert.equal(ui.node('resource-list').children.length, 2);
  const requests = ui.calls.filter(call => /^packages\/payments\/resources\?/.test(call.path));
  assert.equal(requests.length, 2, 'Repeated clicks must not fetch and append the same cursor twice');
  assert.match(requests[1].path, /cursor=opaque\.cursor/);
});

test('inspects declared and effective configuration and compares profiles through the public API', async () => {
  const configuration = {
    declared: {
      view: 'declared', state: 'ready', schemaVersion: 'att-config/v2.12',
      environments: [{ name: 'SIT', state: 'active', default: true }, { name: 'UAT', state: 'active', default: false }],
      globals: {}, sections: [], diagnostics: []
    },
    effective: {
      view: 'effective', state: 'ready', environment: 'SIT',
      globals: { timeoutMs: { state: 'visible', value: 5000 } },
      sections: [{ id: 'dbhelpers', entries: [{ id: 'orders', fields: { url: { state: 'hidden' }, readOnly: { state: 'visible', value: true } } }] }],
      diagnostics: []
    },
    compare: {
      view: 'compare', state: 'ready', leftEnvironment: 'SIT', rightEnvironment: 'UAT',
      fields: [{ path: 'dbhelpers.orders.url', left: { state: 'hidden' }, right: { state: 'hidden' }, change: 'hidden' }],
      diagnostics: []
    }
  };
  const ui = boot({ hash: '#/packages/payments', configuration });
  await waitFor(() => ui.node('configuration-status').textContent.includes('Declared configuration: ready'), 'declared configuration');
  assert.equal(ui.node('configuration-environment').children.length, 2);
  assert.equal(ui.node('configuration-environment').value, 'SIT');
  assert.equal(ui.node('configuration-left').value, 'SIT');
  assert.equal(ui.node('configuration-right').value, 'UAT');
  assert.ok(ui.calls.some(call => call.path === 'packages/payments/configuration?view=declared'));

  ui.node('show-effective-configuration').listeners.click();
  await waitFor(() => ui.node('configuration-status').textContent.includes('Effective configuration for SIT: ready'), 'effective configuration');
  assert.ok(ui.calls.some(call => call.path === 'packages/payments/configuration/effective?environment=SIT&offset=0&limit=50'));
  ui.node('configuration-section').value = 'dbhelpers';
  ui.node('configuration-section').listeners.change();
  await waitFor(() => ui.calls.some(call => call.path.includes('configuration/effective?environment=SIT&section=dbhelpers')), 'selected configuration section');
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(allText(ui.node('configuration-view')).includes('hidden'));
  assert.ok(allText(ui.node('configuration-view')).includes('readOnly'));
  ui.node('submit-form').elements.environment = { value: '' };
  ui.node('use-configuration-environment').listeners.click();
  assert.equal(ui.node('submit-form').elements.environment.value, 'SIT');
  assert.equal(ui.calls.some(call => call.path === 'jobs/run' && call.options.method === 'POST'), false);

  await ui.node('compare-configuration').listeners.click();
  await waitFor(() => ui.node('configuration-status').textContent.includes('Configuration comparison: SIT and UAT: ready'), 'configuration comparison');
  assert.ok(allText(ui.node('configuration-view')).includes('hidden'));
  assert.ok(ui.calls.some(call => call.path === 'packages/payments/configuration/compare?left=SIT&right=UAT&offset=0&limit=50'));
});

test('defaults comparison selectors to different profiles when the default is not first', async () => {
  const configuration = { declared: { view: 'declared', state: 'ready', environments: [
    { name: 'SIT', state: 'active', default: false }, { name: 'UAT', state: 'active', default: true }
  ], globals: {}, sections: [], diagnostics: [] } };
  const ui = boot({ hash: '#/packages/payments', configuration });
  await waitFor(() => ui.node('configuration-status').textContent.includes('Declared configuration: ready'), 'declared profiles');
  assert.equal(ui.node('configuration-left').value, 'UAT');
  assert.equal(ui.node('configuration-right').value, 'SIT');
  assert.equal(ui.node('compare-configuration').disabled, false);
});

test('supports an unprofiled package default and disables profile comparison', async () => {
  const configuration = {
    declared: { view: 'declared', state: 'ready', environments: [], globals: {}, sections: [], diagnostics: [] },
    effective: { view: 'effective', state: 'ready', environment: 'LOCAL', environments: [], globals: { timeoutMs: { state: 'visible', value: 5000, origin: 'global' } }, sections: [], diagnostics: [] }
  };
  const ui = boot({ hash: '#/packages/payments', configuration });
  await waitFor(() => ui.node('configuration-status').textContent.includes('Declared configuration: ready'), 'unprofiled declaration');
  assert.equal(ui.node('configuration-environment').children[0].textContent, 'Package default');
  assert.equal(ui.node('compare-configuration').disabled, true);
  ui.node('show-effective-configuration').listeners.click();
  await waitFor(() => ui.node('configuration-status').textContent.includes('Effective package default configuration: ready'), 'unprofiled effective config');
  assert.ok(ui.calls.some(call => call.path === 'packages/payments/configuration/effective?offset=0&limit=50'));
  ui.node('submit-form').elements.environment = { value: '' };
  ui.node('use-configuration-environment').listeners.click();
  assert.equal(ui.node('submit-form').elements.environment.value, 'LOCAL');
});

test('links a Tool detail to its grouped configuration section', async () => {
  const tool = { resourceId: 'tool.c2FtcGxl', type: 'tool', logicalId: 'sample.lookup', name: 'Lookup', sourceAvailable: false, state: 'ready', references: [], referencedBy: [] };
  const configuration = {
    declared: { view: 'declared', state: 'ready', environments: [{ name: 'SIT', state: 'active', default: true }], globals: {}, sections: [], diagnostics: [] },
    effective: { view: 'effective', state: 'ready', environment: 'SIT', globals: {}, sections: [{ id: 'tools', title: 'Tools', entryCount: 1, entries: [{ id: 'sample.lookup', fields: { timeoutMs: { state: 'visible', value: 1000 } } }] }], diagnostics: [] }
  };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [tool], configuration });
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 1'), 'Tool resource');
  await ui.node('resource-list').children[0].children[0].listeners.click();
  assert.equal(ui.node('resource-configuration-link').hidden, false);
  ui.node('resource-configuration-link').listeners.click();
  await waitFor(() => ui.calls.some(call => call.path.includes('configuration/effective?environment=SIT&section=tools')), 'Tool configuration section');
  assert.equal(ui.node('configuration-search').value, 'sample.lookup');
});

test('links a resolved helper reference to its configuration section', async () => {
  const flow = { resourceId: 'flow.bG9n', type: 'flow', logicalId: 'PAYMENT.flow.v1', name: 'Payment flow', sourceAvailable: false, state: 'ready', references: [{ type: 'dbhelper', logicalId: 'orders', resolution: 'resolved' }], referencedBy: [] };
  const configuration = {
    declared: { view: 'declared', state: 'ready', environments: [{ name: 'SIT', state: 'active', default: true }], globals: {}, sections: [], diagnostics: [] },
    effective: { view: 'effective', state: 'ready', environment: 'SIT', globals: {}, sections: [{ id: 'dbhelpers', title: 'DB helpers', entryCount: 1, entries: [{ id: 'orders', fields: { readOnly: { state: 'visible', value: true } } }] }], diagnostics: [] }
  };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [flow], configuration });
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 1'), 'Flow resource');
  await ui.node('resource-list').children[0].children[0].listeners.click();
  assert.equal(ui.node('resource-configuration-link').hidden, false);
  ui.node('resource-configuration-link').listeners.click();
  await waitFor(() => ui.calls.some(call => call.path.includes('configuration/effective?environment=SIT&section=dbhelpers')), 'helper configuration section');
  assert.equal(ui.node('configuration-search').value, 'orders');
});

test('ignores late configuration responses after navigating away', async () => {
  const declared = { view: 'declared', state: 'ready', environments: [{ name: 'SIT', state: 'active', default: true }], globals: {}, sections: [], diagnostics: [] };
  const ui = boot({ hash: '#/packages/payments', deferConfiguration: true });
  await waitFor(() => ui.calls.some(call => call.path === 'packages/payments/configuration?view=declared'), 'declared configuration request');
  ui.resolveConfiguration(0, declared);
  await waitFor(() => ui.node('configuration-status').textContent.includes('Declared configuration: ready'), 'declared configuration response');
  ui.node('show-effective-configuration').listeners.click();
  await waitFor(() => ui.calls.some(call => call.path.startsWith('packages/payments/configuration/effective?environment=SIT')), 'effective configuration request');

  ui.location.hash = '#/';
  ui.window.listeners.hashchange();
  ui.resolveConfiguration(1, { view: 'effective', state: 'ready', environment: 'STALE', globals: {}, sections: [], diagnostics: [] });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ui.node('configuration-view').textContent.includes('STALE'), false);
});

test('runs only the selected Case from its package-relative suite', async () => {
  const resource = { resourceId: 'case.Y2FzZQ', type: 'case', logicalId: 'PAYMENTS.01', name: 'Payment case', sourceAvailable: false, state: 'ready', provenance: { suite: 'testcase/payments.xlsx' }, references: [], referencedBy: [] };
  const ui = boot({ hash: '#/packages/payments', resourceItems: [resource] });
  await waitFor(() => ui.node('resource-count').textContent.includes('1 of 1'), 'package resource index');
  await ui.node('resource-list').children[0].children[0].listeners.click();
  await ui.node('run-resource-case').listeners.click();
  const post = ui.calls.find(call => call.path === 'jobs/run' && call.options.method === 'POST');
  assert.ok(post);
  assert.deepEqual(JSON.parse(post.options.body), { packageId: 'payments', suites: ['testcase/payments.xlsx'], caseIds: ['PAYMENTS.01'] });
});




test('shows the public API summary for a JSON 403 submission error', async () => {
  const ui = boot({ hash: '#/packages/payments', postForbidden: true });
  await waitFor(() => ui.node('package-title').textContent === 'payments', 'package form');
  const form = ui.node('submit-form');
  form.formValues = [['command', 'run']];
  await form.listeners.submit({ preventDefault() {}, currentTarget: form });
  assert.equal(ui.node('message').textContent, 'State-changing requests must use the same origin.');
  assert.doesNotMatch(ui.node('message').textContent, /Authentication is required/);
});


test('a rejected Debug form can be corrected and submitted', async () => {
  const ui = boot({ hash: '#/packages/payments' });
  await waitFor(() => ui.node('package-title').textContent === 'payments', 'package form');
  const form = ui.node('submit-form');
  form.formValues = [['command', 'debug']];
  await form.listeners.submit({ preventDefault() {}, currentTarget: form });
  assert.match(ui.node('message').textContent, /Debug requires a target type and target ID/);
  assert.equal(form.submitButton.disabled, false);

  form.formValues = [['command', 'run']];
  await form.listeners.submit({ preventDefault() {}, currentTarget: form });
  assert.ok(ui.calls.some(call => call.path === 'jobs/run' && call.options.method === 'POST'));
  assert.equal(ui.location.hash, '#/jobs/J1');
});

test('ignores duplicate submissions while a job request is pending', async () => {
  const ui = boot({ hash: '#/packages/payments', deferSubmit: true });
  await waitFor(() => ui.node('package-title').textContent === 'payments', 'package form');
  const form = ui.node('submit-form');
  form.formValues = [['command', 'run']];
  const event = { preventDefault() {}, currentTarget: form };
  const first = form.listeners.submit(event);
  const second = form.listeners.submit(event);
  assert.equal(ui.pendingSubmissions.length, 1);
  assert.equal(ui.calls.filter(call => call.path === 'jobs/run' && call.options.method === 'POST').length, 1);
  assert.equal(form.submitButton.disabled, true);
  ui.resolveSubmission(0, {
    ok: true, status: 202, headers: { get: () => 'application/json' },
    json: async () => ({ jobId: 'J_PENDING' })
  });
  await Promise.all([first, second]);
  assert.equal(form.submitButton.disabled, false);
  assert.equal(ui.location.hash, '#/jobs/J_PENDING');
});



test('late submission success or failure cannot change a different route', async () => {
  for (const outcome of ['success', 'failure']) {
    const ui = boot({ hash: '#/packages/A', deferSubmit: true });
    await waitFor(() => ui.node('package-title').textContent === 'A', 'package A form');
    const form = ui.node('submit-form');
    form.formValues = [['command', 'run']];
    const pending = form.listeners.submit({ preventDefault() {}, currentTarget: form });
    await waitFor(() => ui.pendingSubmissions.length === 1, 'package A submission');

    ui.location.hash = '#/packages/B';
    ui.window.listeners.hashchange();
    await waitFor(() => ui.node('package-title').textContent === 'B', 'package B form');
    ui.node('message').textContent = 'B view message';

    if (outcome === 'success') {
      ui.resolveSubmission(0, {
        ok: true, status: 202, headers: { get: () => 'application/json' },
        json: async () => ({ jobId: 'J_STALE' })
      });
    } else {
      ui.rejectSubmission(0, new Error('A submission failed late'));
    }
    await pending;
    assert.equal(ui.location.hash, '#/packages/B');
    assert.equal(ui.node('message').textContent, 'B view message');
    assert.equal(form.submitButton.disabled, false);
  }
});


test('pending submission locks only its package across navigation', async () => {
  const ui = boot({ hash: '#/packages/A', deferSubmit: true });
  const form = ui.node('submit-form');
  await waitFor(() => ui.node('package-title').textContent === 'A', 'package A form');
  form.formValues = [['command', 'run']];
  const pendingA = form.listeners.submit({ preventDefault() {}, currentTarget: form });
  await waitFor(() => ui.pendingSubmissions.length === 1, 'package A submission');
  assert.equal(form.submitButton.disabled, true);

  ui.location.hash = '#/packages/B';
  ui.window.listeners.hashchange();
  await waitFor(() => ui.node('package-title').textContent === 'B', 'package B form');
  assert.equal(form.submitButton.disabled, false);

  ui.location.hash = '#/packages/A';
  ui.window.listeners.hashchange();
  await waitFor(() => ui.node('package-title').textContent === 'A', 'return to package A');
  assert.equal(form.submitButton.disabled, true);

  ui.location.hash = '#/packages/B';
  ui.window.listeners.hashchange();
  await waitFor(() => ui.node('package-title').textContent === 'B', 'return to package B');
  assert.equal(form.submitButton.disabled, false);
  const pendingB = form.listeners.submit({ preventDefault() {}, currentTarget: form });
  await waitFor(() => ui.pendingSubmissions.length === 2, 'package B submission');
  assert.deepEqual(ui.calls.filter(call => call.options.method === 'POST').map(call => JSON.parse(call.options.body).packageId), ['A', 'B']);

  ui.resolveSubmission(1, {
    ok: true, status: 202, headers: { get: () => 'application/json' },
    json: async () => ({ jobId: 'J_B' })
  });
  await pendingB;
  ui.window.listeners.hashchange();
  await waitFor(() => ui.streams.length === 1 && ui.node('job-title').textContent === 'Job J_B', 'package B job view');

  ui.resolveSubmission(0, {
    ok: true, status: 202, headers: { get: () => 'application/json' },
    json: async () => ({ jobId: 'J_A' })
  });
  await pendingA;
  assert.equal(ui.location.hash, '#/jobs/J_B');
  assert.equal(ui.node('job-title').textContent, 'Job J_B');
  assert.equal(ui.node('message').textContent, '');
});


test('switching packages clears form values but same-package refresh preserves them', async () => {
  const ui = boot({ hash: '#/packages/A' });
  await waitFor(() => ui.node('package-title').textContent === 'A', 'package A form');
  const form = ui.node('submit-form');
  form.formValues = [
    ['command', 'run'], ['environment', 'SIT'], ['config', 'a/config.yaml'],
    ['suites', 'suite-a'], ['tags', 'package-a'], ['overrides', 'vars.mode=A']
  ];

  ui.window.listeners.hashchange();
  await waitFor(() => ui.node('package-title').textContent === 'A', 'same-package refresh');
  assert.deepEqual(form.formValues, [
    ['command', 'run'], ['environment', 'SIT'], ['config', 'a/config.yaml'],
    ['suites', 'suite-a'], ['tags', 'package-a'], ['overrides', 'vars.mode=A']
  ]);

  ui.location.hash = '#/packages/B';
  ui.window.listeners.hashchange();
  await waitFor(() => ui.node('package-title').textContent === 'B', 'package B form');
  assert.deepEqual(form.formValues, []);
  form.formValues = [['command', 'run']];
  await form.listeners.submit({ preventDefault() {}, currentTarget: form });
  const post = ui.calls.find(call => call.path === 'jobs/run' && call.options.method === 'POST');
  const payload = JSON.parse(post.options.body);
  assert.equal(payload.packageId, 'B');
  for (const field of ['environment', 'config', 'suites', 'tags', 'overrides']) assert.equal(Object.hasOwn(payload, field), false);
});

test('job SSE receives progress while artifact discovery is still pending', async () => {
  const ui = boot({ hash: '#/jobs/J1', deferArtifacts: true });
  await waitFor(() => ui.streams.length === 1 && ui.pendingArtifacts.length === 1, 'SSE and pending artifact lookup');
  const stream = ui.streams[0];
  assert.equal(stream.closed, false);
  stream.emit('progress', { completed: 3, total: 8 }, 1);
  assert.match(ui.node('progress').textContent, /"completed": 3/);

  ui.resolveArtifacts(0, {
    ok: true, status: 200, headers: { get: () => 'application/json' },
    json: async () => ({ items: [{ path: 'report/index.html', size: 42 }] })
  });
  await waitFor(() => ui.node('artifacts').children.length === 1, 'artifact listing');
  assert.match(ui.node('artifacts').children[0].children[0].textContent, /report\/index\.html/);
});



test('a late initial artifact response cannot replace the terminal report list', async () => {
  const ui = boot({ hash: '#/jobs/J1', deferArtifacts: true });
  await waitFor(() => ui.streams.length === 1 && ui.pendingArtifacts.length === 1, 'initial artifact request');
  ui.streams[0].emit('result', { status: 'PASS' }, 1);
  await waitFor(() => ui.pendingArtifacts.length === 2, 'terminal artifact refresh');

  ui.resolveArtifacts(1, {
    ok: true, status: 200, headers: { get: () => 'application/json' },
    json: async () => ({ items: [{ path: 'reports/final.html', size: 128 }] })
  });
  await waitFor(() => ui.node('artifacts').children.length === 1, 'terminal report listing');
  assert.match(ui.node('artifacts').children[0].children[0].textContent, /reports\/final\.html/);

  ui.resolveArtifacts(0, {
    ok: true, status: 200, headers: { get: () => 'application/json' },
    json: async () => ({ items: [] })
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ui.node('artifacts').children.length, 1);
  assert.match(ui.node('artifacts').children[0].children[0].textContent, /reports\/final\.html/);
});


test('terminal job SSE and artifacts load before a deferred result response', async () => {
  const ui = boot({
    hash: '#/jobs/J1', jobStatus: 'PASS', deferResult: true,
    artifactItems: [{ path: 'report.html', size: 12 }]
  });
  await waitFor(() => ui.pendingResults.length === 1, 'deferred terminal result');
  await waitFor(() => ui.streams.length === 1 && ui.node('artifacts').children.length === 1,
    'terminal SSE and artifact list');
  assert.equal(ui.node('result').textContent, '');
  assert.ok(ui.calls.some(call => call.path === 'jobs/J1/events' || call.path === 'jobs/J1/artifacts'));

  ui.resolveResult(0, {
    ok: true, status: 200, headers: { get: () => 'application/json' },
    json: async () => ({ result: { passed: 1 }, diagnostic: null })
  });
  await waitFor(() => ui.node('result').textContent.includes('PASS'), 'terminal result');
  assert.match(ui.node('artifacts').children[0].children[0].textContent, /report\.html/);
});

test('bounds and deduplicates event history, then closes terminal SSE', async () => {
  const ui = boot({ hash: '#/jobs/J1' });
  await waitFor(() => ui.streams.length === 1, 'initial job event stream');
  assert.equal(ui.streams.length, 1);
  const stream = ui.streams[0];
  assert.equal(stream.url, '/tools/att/api/v1/jobs/J1/events');
  for (let i = 1; i <= 600; i++) stream.emit('log', { message: 'entry ' + i }, i);
  assert.equal(ui.node('events').children.length, 500);
  stream.emit('log', { message: 'duplicate event' }, 600);
  assert.equal(ui.node('events').children.length, 500);
  stream.emit('progress', { completed: 17, total: 25 }, 601);
  assert.match(ui.node('progress').textContent, /"completed": 17/);
  ui.state.jobStatus = 'PASS';
  stream.emit('status', { status: 'PASS' }, 602);
  stream.emit('result', { status: 'PASS' }, 603);
  await waitFor(() => stream.closed && ui.node('result').textContent.includes('PASS'), 'terminal job result');
  assert.equal(stream.closed, true);
  assert.match(ui.node('stream-state').textContent, /completed/);
  assert.match(ui.node('result').textContent, /PASS/);
});

test('Debug, Load and Validate forms preserve the public job DTO', async () => {
  const cases = [
    { command: 'debug', fields: [['debugId','D7'],['targetType','flow'],['targetId','PAYMENT.submit'],['overrides','vars.Channel=WEB']],
      verify: p => { assert.equal(p.debugId, 'D7'); assert.deepEqual(p.target, {type:'flow',id:'PAYMENT.submit'}); assert.deepEqual(p.overrides,['vars.Channel=WEB']); } },
    { command: 'load', fields: [['targetType','flow'],['targetId','LOAD.test'],['users','4'],['duration','PT1M']],
      verify: p => { assert.deepEqual(p.target,{type:'flow',id:'LOAD.test'}); assert.deepEqual(p.load,{users:'4',duration:'PT1M'}); } },
    { command: 'validate', fields: [['validationScope','all']],
      verify: p => assert.equal(p.validationScope,'all') }
  ];
  for (const item of cases) {
    const ui = boot({ hash: '#/packages/payments' });
    await waitFor(() => ui.node('package-title').textContent === 'payments', 'package form');
    const form = ui.node('submit-form');
    form.formValues = [['command',item.command], ...item.fields];
    await form.listeners.submit({ preventDefault() {}, currentTarget: form });
    const post = ui.calls.find(call => call.path === 'jobs/' + item.command);
    assert.ok(post);
    const payload = JSON.parse(post.options.body);
    assert.equal(payload.packageId,'payments');
    assert.equal(Object.hasOwn(payload,'packageRoot'),false);
    item.verify(payload);
  }
});

test('leaving a job closes its stream and does not cancel the job', async () => {
  const ui = boot({ hash: '#/jobs/J1' });
  await waitFor(() => ui.streams.length === 1, 'first job event stream');
  const first = ui.streams[0];
  ui.location.hash = '#/jobs/J2';
  ui.window.listeners.hashchange();
  await waitFor(() => ui.streams.length === 2 && ui.node('job-title').textContent === 'Job J2', 'second job event stream');
  assert.equal(first.closed, true);
  assert.equal(ui.streams.length, 2);
  assert.equal(ui.node('job-title').textContent, 'Job J2');
  assert.equal(ui.calls.filter(call => call.options.method === 'DELETE').length, 0);
});

test('cancellation waits for the Server to confirm a terminal status', async () => {
  const ui = boot({ hash: '#/jobs/J1' });
  await waitFor(() => ui.streams.length === 1, 'job event stream before cancellation');
  await ui.node('cancel-job').listeners.click();
  assert.equal(ui.calls.filter(call => call.options.method === 'DELETE').length, 1);
  assert.equal(ui.node('cancel-job').disabled, true);
  assert.match(ui.node('cancel-state').textContent, /waiting for Server confirmation/);
  assert.equal(ui.node('cancel-area').hidden, false);
});

test('a late cancellation failure for a previous job cannot change the current job controls', async () => {
  const ui = boot({ hash: '#/jobs/J1', deferCancel: true });
  await waitFor(() => ui.streams.length === 1, 'first job stream');
  const cancelFirst = ui.node('cancel-job').listeners.click();
  await waitFor(() => ui.pendingCancellations.length === 1, 'first cancellation request');

  ui.location.hash = '#/jobs/J2';
  ui.window.listeners.hashchange();
  await waitFor(() => ui.streams.length === 2 && ui.node('job-title').textContent === 'Job J2', 'second job view');
  const cancelSecond = ui.node('cancel-job').listeners.click();
  await waitFor(() => ui.pendingCancellations.length === 2, 'second cancellation request');
  assert.equal(ui.node('cancel-job').disabled, true);
  assert.match(ui.node('cancel-state').textContent, /waiting for Server confirmation/);

  ui.rejectCancellation(0, new Error('J1 cancellation failed late'));
  await cancelFirst;
  assert.equal(ui.node('cancel-job').disabled, true);
  assert.match(ui.node('cancel-state').textContent, /waiting for Server confirmation/);
  assert.equal(ui.node('message').textContent, '');

  ui.resolveCancellation(1, {
    ok: true, status: 200, headers: { get: () => 'application/json' },
    json: async () => ({ status: 'CANCEL_REQUESTED' })
  });
  await cancelSecond;
  assert.deepEqual(ui.calls.filter(call => call.options.method === 'DELETE').map(call => call.path), ['jobs/J1', 'jobs/J2']);
  assert.equal(ui.node('cancel-job').disabled, true);
  assert.match(ui.node('cancel-state').textContent, /waiting for Server confirmation/);
});


test('preserves logical resource paths in diagnostics while hiding absolute paths', async () => {
  const ui = boot({ hash: '#/jobs/J1' });
  await waitFor(() => ui.streams.length === 1, 'job event stream for diagnostic');
  ui.streams[0].emit('diagnostic', {
    message: 'Missing resource config/templates/payment.yaml; output /srv/att/jobs/J1/report.html'
  }, 1);
  const rendered = ui.node('events').children[0].textContent;
  assert.match(rendered, /config\/templates\/payment\.yaml/);
  assert.doesNotMatch(rendered, /\/srv\/att\/jobs/);
});
test('does not start SSE when the initial job lookup fails', async () => {
  const ui = boot({ hash: '#/jobs/nonexistent', jobMissing: true });
  await waitFor(() => ui.node('message').textContent.includes('Job not found'), 'job lookup error');
  assert.equal(ui.streams.length, 0);
  assert.deepEqual(ui.calls.map(call => call.path), ['version', 'jobs/nonexistent']);
});

test('a late Home jobs snapshot cannot overwrite a newer refresh', async () => {
  const ui = boot({ deferJobLists: true });
  await waitFor(() => ui.pendingHomeJobs.length === 1, 'initial Home jobs request');

  const refresh = ui.node('refresh-jobs').listeners.click();
  await waitFor(() => ui.pendingHomeJobs.length === 2, 'newer Home jobs request');
  const response = status => ({
    ok: true, status: 200, headers: { get: () => 'application/json' },
    json: async () => ({ items: [{ jobId: 'J1', packageId: 'payments', command: 'run', status, createdAt: 'now' }] })
  });
  ui.resolveHomeJobList(1, response('PASS'));
  await refresh;
  assert.equal(ui.node('jobs').children[0].children[3].textContent, 'PASS');

  ui.resolveHomeJobList(0, response('RUNNING'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ui.node('jobs').children[0].children[3].textContent, 'PASS');
});

test('late home failure cannot overwrite an active job view', async () => {
  const ui = boot({ deferHome: true });
  await waitFor(() => ui.calls.some(call => call.path === 'packages')
    && ui.calls.some(call => call.path === 'jobs'), 'home requests');
  ui.location.hash = '#/jobs/J1';
  ui.window.listeners.hashchange();
  await waitFor(() => ui.streams.length === 1, 'active job stream');
  ui.failHome(new Error('late home request failed'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ui.node('connection').textContent, 'Connected');
  assert.equal(ui.node('message').textContent, '');
  assert.equal(ui.streams.length, 1);
});
