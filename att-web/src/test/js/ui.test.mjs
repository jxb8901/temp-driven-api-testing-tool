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

function boot({ hash = '', version = '1', confirmCancel = true, jobMissing = false, deferHome = false, deferSubmit = false, deferCancel = false, postForbidden = false } = {}) {
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
  }
  const elements = new Map();
  const node = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  const document = {
    baseURI: 'https://example.test/tools/att/ui/',
    getElementById: node,
    createElement: () => new Element()
  };
  const location = { hash };
  const window = { listeners: {}, confirm: () => confirmCancel,
    addEventListener(name, listener) { this.listeners[name] = listener; }
  };
  const calls = [], streams = [], pendingSubmissions = [], pendingCancellations = [];
  const state = { jobStatus: 'RUNNING' };
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
    if (deferSubmit && options.method === 'POST') return new Promise((resolve, reject) => pendingSubmissions.push({ resolve, reject }));
    if (deferCancel && options.method === 'DELETE') return new Promise((resolve, reject) => pendingCancellations.push({ resolve, reject }));
    if (deferHome && (path === 'packages' || path === 'jobs')) return delayedHome;
    if (postForbidden && path === 'jobs/run' && options.method === 'POST') {
      return { ok: false, status: 403, headers: { get: () => 'application/json' },
        json: async () => ({ error: { code: 'ATT-SERVER-CROSS-ORIGIN-REQUEST', summary: 'State-changing requests must use the same origin.' } }) };
    }
    if (jobMissing && path === 'jobs/nonexistent') {
      return { ok: false, status: 404, headers: { get: () => 'application/json' },
        json: async () => ({ error: { summary: 'Job not found' } }) };
    }
    let result;
    if (path === 'version') result = { apiVersion: version };
    else if (path === 'packages') result = { items: [{ packageId: 'payments' }] };
    else if (path.startsWith('packages/')) result = { packageId: path.substring(9) };
    else if (path === 'jobs') result = { items: [] };
    else if (/^jobs\/(run|debug|load|validate)$/.test(path) && options.method === 'POST') result = { jobId: 'J1' };
    else if (/^jobs\/[^/]+\/artifacts$/.test(path)) result = { items: [] };
    else if (/^jobs\/[^/]+\/result$/.test(path)) result = { result: { passed: 1 }, diagnostic: null };
    else if (/^jobs\/[^/]+$/.test(path) && options.method === 'DELETE') result = { status: 'CANCEL_REQUESTED' };
    else if (/^jobs\/[^/]+$/.test(path)) result = { jobId: path.split('/')[1], status: state.jobStatus, packageId: 'payments', command: 'run' };
    else throw Error('Unexpected fetch path ' + path);
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => result };
  }
  class FormData {
    constructor(form) { return new Map(form.formValues || []); }
  }
  vm.runInNewContext(script, { document, location, window, fetch, EventSource, URL, Headers, FormData, console }, { filename: 'app.js' });
  return { node, location, window, calls, streams, state, pendingSubmissions, pendingCancellations,
    resolveSubmission: (index, response) => pendingSubmissions[index].resolve(response),
    rejectSubmission: (index, error) => pendingSubmissions[index].reject(error),
    resolveCancellation: (index, response) => pendingCancellations[index].resolve(response),
    rejectCancellation: (index, error) => pendingCancellations[index].reject(error),
    failHome: error => rejectHome(error) };
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
