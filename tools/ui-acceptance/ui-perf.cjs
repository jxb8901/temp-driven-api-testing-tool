#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { gzipSync } = require('node:zlib');
const {
  chromium, firefox, webkit, launchBrowser, startFixture, stopFixture,
} = require('./browser-support.cjs');

const harnessRoot = __dirname;
const repositoryRoot = path.resolve(harnessRoot, '../..');
const fixtureSpec = JSON.parse(fs.readFileSync(path.join(harnessRoot, 'perf-fixtures/scenario.json'), 'utf8'));
const apiFixture = JSON.parse(fs.readFileSync(path.join(harnessRoot, 'perf-fixtures/api.json'), 'utf8'));
const artifactRoot = path.join(harnessRoot, 'artifacts/perf');

function usage() {
  console.log(`Usage: npm run perf -- [options]

Options:
  --label NAME                 Artifact label (default: local-<timestamp>)
  --repeat N                   Repetitions per scenario (default: 3)
  --browser chromium|firefox|webkit  Browser engine (default: chromium)
  --reduced-motion             Emulate prefers-reduced-motion: reduce
  --scenario NAME              Run one scenario or a comma-separated subset
  --fail-on-crash               Exit non-zero when a scenario crashes
  --compare BEFORE AFTER       Print a Markdown comparison from two labels
  --help                       Show this help

The harness expects a built att-web/frontend/dist (or ATT_UI_DIST) and writes
raw JSON plus a Markdown summary below tools/ui-acceptance/artifacts/perf/.`);
}

function parseArgs(argv) {
  const options = { label: `local-${new Date().toISOString().replace(/[:.]/g, '-')}`, repeat: 3, browser: 'chromium', reducedMotion: false, scenarios: null, compare: null, failOnCrash: false };
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--help') { usage(); process.exit(0); }
    if (value === '--reduced-motion') { options.reducedMotion = true; continue; }
    if (value === '--fail-on-crash') { options.failOnCrash = true; continue; }
    if (value === '--label' || value === '--repeat' || value === '--browser' || value === '--scenario') {
      const next = argv[++index];
      if (!next) throw new Error(`${value} needs a value.`);
      if (value === '--label') options.label = next;
      if (value === '--repeat') options.repeat = Number(next);
      if (value === '--browser') options.browser = next;
      if (value === '--scenario') options.scenarios = next.split(',').map(item => item.trim()).filter(Boolean);
      continue;
    }
    if (value === '--compare') {
      const before = argv[++index]; const after = argv[++index];
      if (!before || !after) throw new Error('--compare needs BEFORE and AFTER labels.');
      options.compare = [before, after];
      continue;
    }
    throw new Error(`Unknown option: ${value}`);
  }
  if (!Number.isInteger(options.repeat) || options.repeat < 1) throw new Error('--repeat must be a positive integer.');
  if (!['chromium', 'firefox', 'webkit'].includes(options.browser)) throw new Error('--browser must be chromium, firefox, or webkit.');
  if (!/^[A-Za-z0-9._-]+$/.test(options.label)) throw new Error('--label may contain only letters, numbers, dot, underscore, and dash.');
  return options;
}

function percentile(values, percentileValue) {
  const numbers = values.filter(value => typeof value === 'number' && Number.isFinite(value)).sort((a, b) => a - b);
  if (!numbers.length) return null;
  const rank = (numbers.length - 1) * percentileValue;
  const lower = Math.floor(rank); const upper = Math.ceil(rank);
  return lower === upper ? numbers[lower] : numbers[lower] + (numbers[upper] - numbers[lower]) * (rank - lower);
}

function summarizeSamples(samples) {
  const numericKeys = new Set();
  for (const sample of samples) for (const [key, value] of Object.entries(sample.metrics || {})) {
    if (typeof value === 'number' && Number.isFinite(value)) numericKeys.add(key);
  }
  const summary = {};
  for (const key of numericKeys) {
    const values = samples.map(sample => sample.metrics[key]);
    summary[key] = { median: percentile(values, 0.5), p95: percentile(values, 0.95), raw: values };
  }
  return summary;
}

function gitCommit() {
  try { return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repositoryRoot, encoding: 'utf8' }).trim(); }
  catch { return 'unavailable'; }
}

function browserVersion(browser) {
  try { return browser.version(); } catch { return 'unavailable'; }
}

function packageVersion() {
  try { return require('playwright/package.json').version; } catch { return 'unavailable'; }
}

function uiRoot() {
  const candidate = process.env.ATT_UI_DIST
    ? path.resolve(process.env.ATT_UI_DIST)
    : path.join(repositoryRoot, 'att-web/frontend/dist');
  if (!fs.existsSync(path.join(candidate, 'index.html'))) {
    throw new Error(`Built UI not found at ${candidate}. Run: cd att-web/frontend && npm run build`);
  }
  process.env.ATT_UI_DIST = candidate;
  return candidate;
}

function makeJobs(count) {
  return Array.from({ length: count }, (_, index) => {
    const id = String(count - index).padStart(3, '0');
    const status = index % 11 === 0 ? 'RUNNING' : index % 7 === 0 ? 'FAIL' : 'PASS';
    const createdAt = new Date(Date.UTC(2026, 9, 22, 9, 0, 0) - index * 60_000).toISOString();
    return { jobId: `PERF-${id}`, packageId: index % 2 ? 'ledger' : 'payments', command: index % 3 ? 'run' : 'load', executionType: index % 3 ? 'run' : 'load', principal: index % 2 ? 'perf-bot' : 'qa-smoke', status, createdAt, startedAt: createdAt, finishedAt: status === 'RUNNING' ? null : new Date(Date.parse(createdAt) + 1200).toISOString(), exitCode: status === 'PASS' ? 0 : status === 'RUNNING' ? null : 1 };
  });
}

function makeSchedules(count) {
  return Array.from({ length: count }, (_, index) => {
    const nextRunAt = new Date(Date.now() + (index + 1) * 60_000).toISOString();
    return { id: `PERF-SCHED-${String(index + 1).padStart(3, '0')}`, name: `Perf schedule ${String(index + 1).padStart(3, '0')}`, description: 'Synthetic UI performance schedule', tags: ['performance'], packageId: index % 2 ? 'ledger' : 'payments', executionType: index % 3 ? 'load' : 'run', createdBy: index % 2 ? 'perf-bot' : 'qa-smoke', scenario: 'load/perf.yaml', environment: 'SIT', timezone: 'Asia/Hong_Kong', cron: `${index % 60} * * * *`, load: {}, maxConcurrentRuns: 1, enabled: index % 5 !== 0, nextRunAt, createdAt: new Date(Date.now() - index * 86_400_000).toISOString() };
  });
}

function makeCatalog(count) {
  return Array.from({ length: count }, (_, index) => ({ packageId: index % 2 ? 'ledger' : 'payments', resourceId: `scenario-${String(index + 1).padStart(3, '0')}`, path: `load/scenario-${String(index + 1).padStart(3, '0')}.yaml`, kind: 'scenario', validation: 'valid', model: index % 2 ? 'arrivalRate' : 'virtualUsers', workloadCount: 2, sourceAvailable: true, runNow: true, schedule: true, createQuickLoad: false }));
}

function makeBuckets(count) {
  const start = Date.UTC(2026, 9, 22, 9, 0, 0);
  return Array.from({ length: count }, (_, index) => ({ epochMs: start + index * 1000, completedTps: 40 + (index % 17), p95Ms: 80 + (index % 31), p99Ms: 120 + (index % 43) }));
}

function makeEvents(start, count, includeTerminal = false) {
  return Array.from({ length: count }, (_, offset) => {
    const id = start + offset;
    const type = id === start ? 'status' : id % 19 === 0 ? 'diagnostic' : id % 3 === 0 ? 'progress' : 'log';
    const data = type === 'status'
      ? { status: 'RUNNING', sequence: id, console: 'Performance fixture started', message: 'Performance fixture started' }
      : type === 'progress'
        ? { status: 'RUNNING', sequence: id, progress: id % 100, console: `perf event ${id}` }
        : type === 'diagnostic'
          ? { severity: 'INFO', sequence: id, message: `diagnostic ${id}` }
          : { sequence: id, console: `perf event ${id}`, message: `event ${id}` };
    return { id: String(id), type, data };
  }).concat(includeTerminal ? [{ id: String(start + count), type: 'result', data: { status: 'PASS', sequence: start + count, message: 'Performance fixture complete' } }] : []);
}

function makeResult(bucketCount) {
  const buckets = makeBuckets(bucketCount);
  return { job: { jobId: fixtureSpec.jobId, command: 'load', packageId: fixtureSpec.packageId, status: 'PASS', exitCode: 0 }, result: { status: 'PASS', metrics: { completed: bucketCount * 40, completedTps: 42, p95Ms: 96, p99Ms: 144, runtimeError: 0, measuredRuntimeError: 0, buckets } } };
}

function apiPayload(pathname, url, plan) {
  const pathValue = pathname.replace(/^\/att\/api\/v1\/?/, '');
  if (pathValue === 'version') return apiFixture.version;
  if (pathValue === 'auth/capabilities') return apiFixture.capabilities;
  if (pathValue === 'packages') return apiFixture.packages;
  if (pathValue === 'jobs') return { items: makeJobs(fixtureSpec.jobs) };
  if (pathValue === 'metrics') return apiFixture.metrics;
  if (pathValue === 'configuration' || pathValue.startsWith('packages/') && /\/configuration(?:$|\?)/.test(pathValue)) return apiFixture.configuration;
  if (pathValue.includes('/configuration/files')) return apiFixture.configurationFiles;
  if (pathValue.includes('/configuration/source')) return apiFixture.configurationSource;
  if (pathValue.includes('/load-catalog')) return { items: makeCatalog(100), total: 100, nextCursor: null };
  if (pathValue.includes('/resources')) return { items: [{ type: 'template', resourceId: 'checkout', logicalId: 'checkout', name: 'Checkout template', state: 'ready', metadataState: 'available', sourceAvailable: true }], total: 1, nextCursor: null };
  if (pathValue.includes('/artifacts')) return { items: [] };
  if (pathValue.endsWith('/result')) return makeResult(plan.bucketCount || 100);
  const jobMatch = /^jobs\/([^/]+)$/.exec(pathValue);
  if (jobMatch) return { ...makeJobs(1)[0], jobId: decodeURIComponent(jobMatch[1]), packageId: fixtureSpec.packageId, command: 'load', executionType: 'load', status: 'RUNNING', performance: { timings: { queueMs: 4, executionMs: 950 }, worker: { cpuMs: 120, heapBytes: 2048 } } };
  if (pathValue === 'load-schedules' || pathValue === 'schedules') return { items: makeSchedules(fixtureSpec.schedules) };
  const scheduleExecutions = /^(?:load-schedules|schedules)\/([^/]+)\/executions/.exec(pathValue);
  if (scheduleExecutions) return { items: Array.from({ length: 20 }, (_, index) => ({ occurrenceId: `PERF-O-${index + 1}`, scheduleId: scheduleExecutions[1], scheduledFor: new Date(Date.now() - index * 3_600_000).toISOString(), triggerType: 'scheduled', status: 'submitted', jobId: `PERF-${String(index + 1).padStart(3, '0')}`, jobStatus: index === 0 ? 'RUNNING' : 'PASS' })) };
  return {};
}

function sseLine(event) {
  return `id: ${event.id}\nevent: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`;
}

function sseBody(events) {
  return `retry: 5\n\n${events.map(sseLine).join('')}`;
}

function pageInstrumentation() {
  const state = {
    eventSourceInstances: {}, incomingEvents: 0, lastEventAt: 0, lastEventId: null,
    longTasks: [], mutationBatches: 0, frameDurations: [], startedAt: 0,
  };
  window.__attPerf = state;
  const NativeEventSource = window.EventSource;
  const nativeAddEventListener = NativeEventSource.prototype.addEventListener;
  NativeEventSource.prototype.addEventListener = function addEventListener(type, listener, options) {
    if (['status', 'progress', 'log', 'diagnostic', 'result', 'message'].includes(type)) {
      const wrapped = function wrappedEvent(event) {
        state.incomingEvents += 1;
        state.lastEventAt = performance.now();
        state.lastEventId = event.lastEventId || null;
        state.lastEventDisplayedAt = 0;
        try {
          const data = JSON.parse(event.data);
          if (data && typeof data.console === 'string') { state.lastConsoleLine = data.console; state.lastConsoleAt = state.lastEventAt; state.lastConsoleDisplayedAt = 0; }
        } catch { /* non-JSON payloads have no console line */ }
        if (type === 'result') { state.terminalEventAt = state.lastEventAt; state.terminalDisplayedAt = 0; }
        return listener.call(this, event);
      };
      return nativeAddEventListener.call(this, type, wrapped, options);
    }
    return nativeAddEventListener.call(this, type, listener, options);
  };
  window.EventSource = new Proxy(NativeEventSource, {
    construct(target, args, newTarget) {
      const url = String(args[0]);
      const match = /\/jobs\/([^/]+)\/events/.exec(url);
      const jobId = match ? decodeURIComponent(match[1]) : url;
      state.eventSourceInstances[jobId] = (state.eventSourceInstances[jobId] || 0) + 1;
      return Reflect.construct(target, args, newTarget);
    },
  });
  const longTaskObserver = window.PerformanceObserver && new PerformanceObserver(list => state.longTasks.push(...list.getEntries().map(entry => entry.duration)));
  try { longTaskObserver?.observe({ type: 'longtask', buffered: true }); } catch { /* Firefox/WebKit may not expose longtask. */ }
  let previousFrame = performance.now();
  // Display latency: the first frame on which the newest event's ID (Events
  // view, `#id`) or console line (Live Output) is in the DOM, and the first
  // frame after the terminal event on which a terminal status is shown.
  const displayed = id => {
    const root = document.body;
    if (!root || !id) return false;
    for (const code of root.querySelectorAll('.att-job-events code')) if (code.textContent === `#${id}`) return true;
    return false;
  };
  const consoleDisplayed = line => {
    const consoleText = document.querySelector('.att-console')?.textContent || '';
    return Boolean(line) && (consoleText.endsWith(line) || consoleText.includes(`${line}\n`));
  };
  const terminalShown = () => [...document.querySelectorAll('.att-status-badge')].some(badge => badge.textContent === 'PASS')
    || [...document.querySelectorAll('[role="status"]')].some(node => node.textContent === 'SSE completed');
  const frame = now => {
    state.frameDurations.push(now - previousFrame); previousFrame = now;
    if (state.lastEventAt && !state.lastEventDisplayedAt && displayed(state.lastEventId)) state.lastEventDisplayedAt = performance.now();
    if (state.lastConsoleAt && !state.lastConsoleDisplayedAt && consoleDisplayed(state.lastConsoleLine)) state.lastConsoleDisplayedAt = performance.now();
    if (state.terminalEventAt && !state.terminalDisplayedAt && terminalShown()) state.terminalDisplayedAt = performance.now();
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  const observeMutations = () => {
    const mutationObserver = new MutationObserver(() => { state.mutationBatches += 1; });
    if (document.documentElement) mutationObserver.observe(document.documentElement, { subtree: true, childList: true, attributes: true, characterData: true });
  };
  if (document.documentElement) observeMutations(); else document.addEventListener('DOMContentLoaded', observeMutations, { once: true });
}

async function jsonResponse(route, payload) {
  await route.fulfill({ status: 200, headers: { 'cache-control': 'no-store', 'content-type': 'application/json; charset=utf-8' }, body: JSON.stringify(payload) });
}

async function installFixtureRoutes(page, plan) {
  let eventConnections = 0;
  await page.route('**/att/api/v1/**', async route => {
    const requestUrl = new URL(route.request().url());
    if (requestUrl.pathname.endsWith('/events')) {
      eventConnections += 1;
      plan.sseRequests = eventConnections;
      const lastEventId = Number((await route.request().allHeaders())['last-event-id'] || 0);
      if (plan.stream === 'burst') {
        // Playwright-fulfilled SSE responses never carry Last-Event-ID on the
        // browser's reconnect, so the burst is served by the real fixture
        // server, which records the reconnect cursor (/__perf/last-event-ids).
        const target = new URL('/__perf/burst-events', requestUrl.origin);
        target.searchParams.set('count', String(fixtureSpec.burstEvents));
        await route.continue({ url: target.toString() });
        return;
      }
      const count = plan.stream === 'steady' ? Math.max(1, Math.round((plan.rate * fixtureSpec.steadyDurationMs) / 1000)) : fixtureSpec.retainedEvents;
      const events = makeEvents(lastEventId ? lastEventId + 1 : 1, count, true);
      await route.fulfill({ status: 200, headers: { 'cache-control': 'no-cache', 'connection': 'keep-alive', 'content-type': 'text/event-stream' }, body: sseBody(events) });
      return;
    }
    await jsonResponse(route, apiPayload(requestUrl.pathname, requestUrl, plan));
  });
  return () => eventConnections;
}

async function beginMeasurement(page, expectedIds) {
  await page.evaluate(ids => {
    window.__attPerf.startedAt = performance.now();
    window.__attPerf.expectedIds = ids;
    window.__attPerf.routeReadyAt = null;
  }, expectedIds);
}

async function markReady(page) {
  await page.evaluate(() => { window.__attPerf.routeReadyAt = performance.now(); });
}

async function inputLatency(page) {
  const input = page.locator('input[type="search"]:visible').first();
  if (await input.count() === 0) return null;
  const start = await page.evaluate(() => performance.now());
  await input.fill('PERF');
  const end = await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(performance.now())))));
  return end - start;
}

async function collectMetrics(page, inputLatencyMs) {
  return page.evaluate(inputLatencyValue => {
    const state = window.__attPerf;
    const eventList = document.querySelector('ol.att-job-events[aria-label="Structured job events"]');
    const ids = [...(eventList?.querySelectorAll('li code') || [])].map(node => node.textContent?.replace(/^#/, '') || '').filter(Boolean);
    const numericIds = ids.map(Number).filter(Number.isFinite);
    const liveOutput = document.querySelector('pre[aria-label="Live output"]');
    const renderedConsoleLines = liveOutput
      ? liveOutput.textContent.split('\n').filter(line => line.length > 0).length
      : 0;
    const renderedCount = liveOutput ? renderedConsoleLines : numericIds.length;
    const duplicates = numericIds.length - new Set(numericIds).size;
    const expected = Array.isArray(state.expectedIds) ? state.expectedIds : [];
    const expectedVisible = expected.slice(-numericIds.length);
    const missing = expectedVisible.filter((id, index) => String(id) !== String(numericIds[index])).length;
    const eventOrderMatchesJournal = numericIds.length > 0 ? duplicates === 0 && missing === 0 : null;
    const resources = performance.getEntriesByType('resource').filter(entry => /\.(?:js|css)(?:[?#]|$)/.test(entry.name));
    const heap = performance.memory && typeof performance.memory.usedJSHeapSize === 'number' ? performance.memory.usedJSHeapSize : null;
    return {
      routeReadyMs: state.routeReadyAt === null ? null : state.routeReadyAt - state.startedAt,
      longTaskCount: state.longTasks.length,
      longTaskOver50Ms: state.longTasks.filter(value => value > 50).length,
      maxLongTaskMs: state.longTasks.length ? Math.max(...state.longTasks) : 0,
      mutationBatches: state.mutationBatches,
      frameCount: state.frameDurations.length,
      frameOver16Ms: state.frameDurations.filter(value => value > 16).length,
      domNodes: document.getElementsByTagName('*').length,
      jsHeapBytes: heap,
      incomingEvents: state.incomingEvents,
      renderedEvents: renderedCount,
      renderedConsoleLines,
      // Events view: newest event ID; Live Output: newest console-bearing event.
      latestDisplayedEventDelayMs: state.lastEventAt && state.lastEventDisplayedAt ? state.lastEventDisplayedAt - state.lastEventAt
        : state.lastConsoleAt && state.lastConsoleDisplayedAt ? state.lastConsoleDisplayedAt - state.lastConsoleAt : null,
      terminalStatusDelayMs: state.terminalEventAt && state.terminalDisplayedAt ? state.terminalDisplayedAt - state.terminalEventAt : null,
      duplicateEventIds: duplicates,
      missingEventIds: missing,
      eventOrderMatchesJournal,
      eventSourceInstances: { ...state.eventSourceInstances },
      inputToNextPaintMs: inputLatencyValue,
      transferredJsCssBytes: resources.reduce((total, entry) => total + (entry.transferSize || entry.encodedBodySize || 0), 0),
      resourceCountJsCss: resources.length,
    };
  }, inputLatencyMs);
}

function distChunkGzip() {
  const dist = process.env.ATT_UI_DIST ? path.resolve(process.env.ATT_UI_DIST) : path.join(repositoryRoot, 'att-web/frontend/dist');
  const manifestPath = path.join(dist, '.vite/manifest.json');
  if (!fs.existsSync(manifestPath)) return [];
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const seen = new Set();
  return Object.entries(manifest).flatMap(([source, chunk]) => [chunk.file, ...(chunk.css || [])].map(file => ({ source, file, dynamic: Boolean(chunk.isDynamicEntry) })))
    .filter(item => /\.(?:js|css)$/.test(item.file) && !seen.has(item.file) && seen.add(item.file))
    .map(item => ({ ...item, rawBytes: fs.statSync(path.join(dist, item.file)).size, gzipBytes: gzipSync(fs.readFileSync(path.join(dist, item.file))).byteLength }));
}

async function waitForRoute(page, origin, hash, heading) {
  await page.goto(`${origin}/att/ui/${hash}`);
  await page.getByRole('main').waitFor({ state: 'visible', timeout: 8_000 });
  if (heading) await page.getByRole('heading', { name: heading, exact: true }).first().waitFor({ state: 'visible', timeout: 8_000 });
}

async function runStep(state, label, action) {
  console.log(`  [${state.name}] ${label}`);
  state.activeStep = label;
  try { return await action(); }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (state.crashed || /Target crashed|page has been closed/i.test(message)) {
      state.crashed = true;
      state.crashStep = state.crashStep || label;
      state.crashElapsedMs = state.crashElapsedMs ?? (Date.now() - state.startedAt);
      console.error(`  [${state.name}] CRASHED at ${state.crashStep} (${state.crashElapsedMs}ms)`);
      return null;
    }
    console.error(`  [${state.name}] FAILED ${label}: ${message}`);
    state.pageErrors.push({ step: label, message });
    return null;
  }
}

async function runScenario(context, origin, scenario) {
  const page = await context.newPage();
  const state = { name: scenario.name, pageErrors: [], plan: { stream: scenario.stream, rate: scenario.rate, bucketCount: scenario.bucketCount }, page, activeStep: 'initialization', startedAt: Date.now(), crashed: false, crashStep: null, crashElapsedMs: null };
  page.on('crash', () => {
    if (state.crashed) return;
    state.crashed = true;
    state.crashStep = state.activeStep;
    state.crashElapsedMs = Date.now() - state.startedAt;
    console.error(`  [${scenario.name}] CRASHED at ${state.crashStep} (${state.crashElapsedMs}ms)`);
  });
  page.on('pageerror', error => { state.pageErrors.push({ step: 'pageerror', message: error.message }); console.error(`  [${scenario.name}] PAGE ERROR: ${error.message}`); });
  page.on('console', message => { if (message.type() === 'error') { state.pageErrors.push({ step: 'console.error', message: message.text() }); console.error(`  [${scenario.name}] CONSOLE ERROR: ${message.text()}`); } });
  await installFixtureRoutes(page, state.plan);
  const expectedCount = scenario.stream === 'burst' ? fixtureSpec.burstEvents + 1 : (scenario.stream ? Math.max(1, Math.round((scenario.rate || fixtureSpec.retainedEvents) * fixtureSpec.steadyDurationMs / 1000)) + 1 : 0);
  const expectedIds = expectedCount ? Array.from({ length: expectedCount }, (_, index) => index + 1) : [];
  await beginMeasurement(page, expectedIds);
  const started = Date.now();
  await runStep(state, `open ${scenario.route}`, () => waitForRoute(page, origin, scenario.route, scenario.heading));
  if (state.crashed) return { name: scenario.name, crashed: true, crashStep: state.crashStep, crashElapsedMs: state.crashElapsedMs, metrics: {}, pageErrors: state.pageErrors };
  await runStep(state, 'mark ready', () => markReady(page));
  if (state.crashed) return { name: scenario.name, crashed: true, crashStep: state.crashStep, crashElapsedMs: state.crashElapsedMs, metrics: {}, pageErrors: state.pageErrors };
  await runStep(state, 'exercise typed search/filter', () => inputLatency(page));
  if (scenario.action) await runStep(state, scenario.action.label, () => scenario.action.run(page, state));
  if (scenario.stream) {
    await runStep(state, 'wait for fixture stream and replay', () => page.waitForFunction(count => window.__attPerf.incomingEvents >= count, expectedCount, { timeout: 8_000 }));
    await page.waitForTimeout(50);
  } else await page.waitForTimeout(120);
  const inputMs = await inputLatency(page);
  if (state.crashed) return { name: scenario.name, crashed: true, crashStep: state.crashStep, crashElapsedMs: state.crashElapsedMs, metrics: {}, pageErrors: state.pageErrors };
  const metrics = await collectMetrics(page, inputMs);
  metrics.sseRequestCount = state.plan.sseRequests || 0;
  for (const [jobId, count] of Object.entries(metrics.eventSourceInstances || {})) {
    if (count > 1) state.pageErrors.push({ step: 'EventSource instance invariant', message: `Job ${jobId} created ${count} EventSource instances in one tab.` });
  }
  if (scenario.stream === 'burst' && metrics.sseRequestCount < 2) state.pageErrors.push({ step: 'SSE reconnect fixture', message: 'The 10,000-event burst did not observe the required Last-Event-ID reconnect.' });
  if (scenario.stream === 'burst') {
    const response = await page.request.get(new URL('/__perf/last-event-ids?reset=1', page.url()).toString());
    metrics.reconnectLastEventIds = (await response.json()).lastEventIds;
  }
  if (scenario.stream === 'burst' && !metrics.reconnectLastEventIds.includes(fixtureSpec.burstEvents)) state.pageErrors.push({ step: 'SSE reconnect fixture', message: `The burst reconnect resumed from Last-Event-ID ${metrics.reconnectLastEventIds.join(', ') || 'none'} instead of ${fixtureSpec.burstEvents}.` });
  const durationMs = Date.now() - started;
  await page.close().catch(() => {});
  return { name: scenario.name, crashed: state.crashed, crashStep: state.crashStep, crashElapsedMs: state.crashElapsedMs, metrics: { ...metrics, durationMs }, pageErrors: state.pageErrors };
}

function routeScenario(name, hash, heading) { return { name, route: hash, heading }; }

function scenariosFor(options) {
  const steady = fixtureSpec.steadyRates.map(rate => ({ name: `live-steady-${rate}/s`, route: `#/jobs/${fixtureSpec.jobId}/console?view=live`, heading: `Job ${fixtureSpec.jobId}`, stream: 'steady', rate, action: { label: 'pause auto-scroll', run: async page => { const button = page.getByRole('button', { name: 'Pause auto-scroll' }); if (await button.count()) await button.click(); } } }));
  const scenarios = [
    ...steady,
    { name: 'live-burst-10k-replay-reconnect', route: `#/jobs/${fixtureSpec.jobId}/console?view=events`, heading: `Job ${fixtureSpec.jobId}`, stream: 'burst', action: { label: 'check ordered visible events', run: async page => page.getByRole('tab', { name: 'Events', exact: true }).click() } },
    { name: 'job-detail-500-retained-events', route: `#/jobs/${fixtureSpec.jobId}/console?view=events`, heading: `Job ${fixtureSpec.jobId}`, stream: 'retained', action: { label: 'open Events tab', run: async page => page.getByRole('tab', { name: 'Events', exact: true }).click() } },
    ...fixtureSpec.metricBuckets.map(bucketCount => ({ name: `metrics-${bucketCount}-buckets`, route: `#/jobs/${fixtureSpec.jobId}`, heading: `Job ${fixtureSpec.jobId}`, bucketCount, action: { label: `open Metrics tab (${bucketCount} buckets)`, run: async page => { await page.getByRole('tab', { name: 'Metrics', exact: true }).click(); await page.getByRole('heading', { name: 'Load time-series buckets', exact: true }).waitFor({ state: 'visible', timeout: 8_000 }); } } })),
    { name: 'jobs-list-100-filter-sort', ...routeScenario('jobs-list-100-filter-sort', '#/jobs', 'Jobs'), action: { label: 'change sort', run: async page => { const select = page.getByLabel('Sort'); await select.selectOption('oldest'); } } },
    { name: 'schedules-100-countdown', ...routeScenario('schedules-100-countdown', '#/schedules', 'Schedules'), action: { label: 'filter schedules', run: async page => { const input = page.getByRole('searchbox'); await input.fill('Perf schedule'); } } },
    ...[
      ['dashboard', '#/', 'Dashboard'], ['explorer', '#/packages/payments', 'Package Explorer'], ['configuration', '#/configuration', 'Configuration'],
      ['load', '#/advanced-load', 'LOAD Catalog'], ['jobs', '#/jobs', 'Jobs'], ['schedules', '#/schedules', 'Schedules'], ['settings', '#/settings', 'Settings'],
    ].map(([name, route, heading]) => ({ name: `navigation-cold-${name}`, route, heading })),
    { name: 'navigation-warm-core-routes', route: '#/', heading: 'Dashboard', action: { label: 'switch core routes repeatedly', run: async page => { for (const hash of ['#/packages/payments', '#/configuration', '#/advanced-load', '#/jobs', '#/schedules', '#/settings', '#/']) { await page.evaluate(value => { window.location.hash = value; }, hash); await page.waitForTimeout(50); } } } },
    { name: 'resilience-hidden-tab-and-paused-scroll', route: `#/jobs/${fixtureSpec.jobId}/console?view=live`, heading: `Job ${fixtureSpec.jobId}`, stream: 'steady', rate: 50, action: { label: 'emulate hidden tab and pause scroll', run: async page => { await page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' }); Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); }); const button = page.getByRole('button', { name: 'Pause auto-scroll' }); if (await button.count()) await button.click(); } } },
  ];
  if (!options.scenarios) return scenarios;
  const wanted = new Set(options.scenarios);
  const selected = scenarios.filter(scenario => wanted.has(scenario.name));
  if (selected.length !== wanted.size) throw new Error(`Unknown scenario. Available: ${scenarios.map(scenario => scenario.name).join(', ')}`);
  return selected;
}

function markdownReport(report) {
  const lines = [`# UI performance report: ${report.label}`, '', `Generated at ${report.generatedAt}. This report has no numeric pass/fail thresholds.`, '', '## Environment', '', `- Commit: \`${report.environment.gitCommit}\``, `- OS: ${report.environment.os}`, `- CPU count: ${report.environment.cpuCount}`, `- Browser: ${report.environment.browser} ${report.environment.browserVersion}`, `- Playwright: ${report.environment.playwrightVersion}`, `- Reduced motion: ${report.environment.reducedMotion ? 'yes' : 'no'}`, `- UI dist: \`${report.environment.uiDist}\``, '', '## Scenario summary', '', '| Scenario | Crash | Route ready p50 / p95 (ms) | Input-to-next-paint p50 / p95 (ms) | Long tasks >50 ms | DOM nodes | Incoming / rendered | Latest event delay p95 (ms) | Terminal delay p95 (ms) | EventSource instances |', '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'];
  for (const scenario of report.scenarios) {
    const s = scenario.summary; const value = key => s[key] ? `${format(s[key].median)} / ${format(s[key].p95)}` : 'n/a';
    const input = s.inputToNextPaintMs ? `${format(s.inputToNextPaintMs.median)} / ${format(s.inputToNextPaintMs.p95)}` : 'no input';
    const latest = value('latestDisplayedEventDelayMs'); const terminal = value('terminalStatusDelayMs');
    const incoming = s.incomingEvents ? format(s.incomingEvents.median) : 'n/a'; const rendered = s.renderedEvents ? format(s.renderedEvents.median) : 'n/a';
    const crash = s.crashed?.count ? `CRASHED at ${s.crashSteps.join(', ')}` : 'no';
    lines.push(`| ${scenario.name} | ${crash} | ${value('routeReadyMs')} | ${input} | ${format(s.longTaskOver50Ms?.median)} | ${format(s.domNodes?.median)} | ${incoming} / ${rendered} | ${latest} | ${terminal} | ${format(s.eventSourceInstancesTotal?.median)} |`);
  }
  lines.push('', '## Integrity and resources', '', '| Scenario | Duplicate IDs | Missing/order mismatches | Journal order | SSE requests | JS heap p50 (bytes) | JS/CSS transferred p50 (bytes) |', '| --- | ---: | ---: | --- | ---: | ---: | ---: |');
  for (const scenario of report.scenarios) {
    const s = scenario.summary;
    const orderValues = scenario.samples.map(sample => sample.metrics.eventOrderMatchesJournal);
    const order = orderValues.every(value => value === null) ? 'unavailable' : orderValues.every(value => value === true) ? 'true' : 'false';
    lines.push(`| ${scenario.name} | ${format(s.duplicateEventIds?.median)} | ${format(s.missingEventIds?.median)} | ${order} | ${format(s.sseRequestCount?.median)} | ${format(s.jsHeapBytes?.median)} | ${format(s.transferredJsCssBytes?.median)} |`);
  }
  lines.push('', '## Bundle gzip sizes', '', '| Asset | Lazy entry | Raw bytes | Gzip bytes |', '| --- | --- | ---: | ---: |');
  for (const chunk of report.bundleGzip) lines.push(`| ${chunk.file} | ${chunk.dynamic ? 'yes' : 'no'} | ${chunk.rawBytes} | ${chunk.gzipBytes} |`);
  lines.push('', '## Raw samples', '', 'The companion JSON file contains every repetition and all captured fields, including long-task and frame samples, duplicate/missing Event-ID diagnostics, JS heap availability, mutation/rAF proxies, transferred JS/CSS bytes, and per-chunk gzip sizes.', '');
  return lines.join('\n');
}

function format(value) { return value === null || value === undefined || !Number.isFinite(value) ? 'n/a' : Number(value).toFixed(1); }

function aggregateScenario(samples) {
  const normalized = samples.map(sample => ({ ...sample, metrics: { ...sample.metrics, eventSourceInstancesTotal: Object.values(sample.metrics.eventSourceInstances || {}).reduce((sum, value) => sum + value, 0) } }));
  const summary = summarizeSamples(normalized);
  summary.crashed = { count: normalized.filter(sample => sample.crashed).length, total: normalized.length };
  summary.crashElapsedMs = summarizeSamples(normalized.map(sample => ({ metrics: { crashElapsedMs: sample.crashElapsedMs } }))).crashElapsedMs;
  summary.crashSteps = [...new Set(normalized.filter(sample => sample.crashed).map(sample => sample.crashStep).filter(Boolean))];
  return { name: samples[0].name, samples: normalized, summary };
}

function readReport(label) {
  const reportPath = path.join(artifactRoot, label, 'perf.json');
  if (!fs.existsSync(reportPath)) throw new Error(`Missing report: ${reportPath}`);
  return JSON.parse(fs.readFileSync(reportPath, 'utf8'));
}

function compareReports(beforeLabel, afterLabel) {
  const before = readReport(beforeLabel); const after = readReport(afterLabel);
  const beforeMap = new Map(before.scenarios.map(item => [item.name, item]));
  const lines = [`| Scenario | Metric | Before p95 | After p95 | Delta |`, '| --- | --- | ---: | ---: | ---: |'];
  for (const current of after.scenarios) {
    const prior = beforeMap.get(current.name); if (!prior) continue;
    for (const key of ['routeReadyMs', 'inputToNextPaintMs', 'longTaskOver50Ms', 'domNodes', 'latestDisplayedEventDelayMs', 'terminalStatusDelayMs']) {
      const oldValue = prior.summary[key]?.p95; const newValue = current.summary[key]?.p95;
      if (oldValue === null || oldValue === undefined || newValue === null || newValue === undefined) continue;
      lines.push(`| ${current.name} | ${key} | ${format(oldValue)} | ${format(newValue)} | ${format(newValue - oldValue)} |`);
    }
  }
  console.log(`# UI performance comparison: ${beforeLabel} → ${afterLabel}\n\n${lines.join('\n')}`);
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.compare) { compareReports(...options.compare); return; }
  const uiDist = uiRoot();
  const selectedScenarios = scenariosFor(options);
  const browserType = { chromium, firefox, webkit }[options.browser];
  const fixture = await startFixture();
  let browser;
  const report = {
    label: options.label, generatedAt: new Date().toISOString(), fixture: fixtureSpec,
    environment: { gitCommit: gitCommit(), os: `${os.platform()} ${os.release()} ${os.arch()}`, cpuCount: os.cpus().length, browser: options.browser, browserVersion: null, playwrightVersion: packageVersion(), reducedMotion: options.reducedMotion, uiDist },
    scenarios: [], bundleGzip: distChunkGzip(),
  };
  try {
    browser = await launchBrowser(browserType);
    report.environment.browserVersion = browserVersion(browser);
    for (let repeat = 1; repeat <= options.repeat; repeat += 1) {
      console.log(`Repeat ${repeat}/${options.repeat}`);
      for (const scenario of selectedScenarios) {
        const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: options.reducedMotion ? 'reduce' : 'no-preference' });
        await context.addInitScript({ content: `(${pageInstrumentation.toString()})();` });
        let result;
        try { result = await runScenario(context, fixture.origin, scenario); }
        catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          result = { name: scenario.name, crashed: /Target crashed|page has been closed/i.test(message), crashStep: 'unknown', crashElapsedMs: null, metrics: {}, pageErrors: [{ step: 'scenario', message }] };
        } finally {
          await context.close().catch(() => {});
        }
        let aggregate = report.scenarios.find(item => item.name === result.name);
        if (!aggregate) { aggregate = { name: result.name, samples: [] }; report.scenarios.push(aggregate); }
        aggregate.samples.push({ repeat, metrics: result.metrics, pageErrors: result.pageErrors });
        console.log(result.crashed ? `  [${result.name}] CRASHED at ${result.crashStep} (${result.crashElapsedMs ?? 'n/a'}ms)` : `  [${result.name}] ready=${format(result.metrics.routeReadyMs)}ms input=${format(result.metrics.inputToNextPaintMs)}ms nodes=${result.metrics.domNodes} incoming=${result.metrics.incomingEvents} rendered=${result.metrics.renderedEvents}`);
      }
    }
    for (const scenario of report.scenarios) {
      const aggregate = aggregateScenario(scenario.samples);
      scenario.samples = aggregate.samples; scenario.summary = aggregate.summary;
    }
    const fatal = report.scenarios.flatMap(scenario => scenario.samples.flatMap(sample => sample.crashed ? [] : (sample.pageErrors || [])));
    const crashes = report.scenarios.flatMap(scenario => scenario.samples.filter(sample => sample.crashed));
    const directory = path.join(artifactRoot, options.label);
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'perf.json'), JSON.stringify(report, null, 2) + '\n');
    fs.writeFileSync(path.join(directory, 'summary.md'), markdownReport(report) + '\n');
    console.log(`Wrote ${path.join(directory, 'perf.json')}`);
    console.log(`Wrote ${path.join(directory, 'summary.md')}`);
    if (fatal.length || (options.failOnCrash && crashes.length)) process.exitCode = 1;
  } finally {
    await browser?.close();
    await stopFixture(fixture);
  }
}

main().catch(error => { console.error(error instanceof Error ? error.stack || error.message : String(error)); process.exitCode = 1; });
