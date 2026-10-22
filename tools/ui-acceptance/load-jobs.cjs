const assert = require('node:assert/strict');
const { launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

const jobId = 'JOB-LOAD-204';
const job = {
  jobId, packageId: 'payments', command: 'load', principal: 'qa-smoke', status: 'RUNNING',
  createdAt: '2026-10-13T09:50:00Z', startedAt: '2026-10-13T09:50:01Z',
  performance: { timings: { queueMs: 4, executionMs: 950 }, worker: { heapUsedBytes: 2048 } },
};
const earlyBucket = Date.parse('2026-10-13T09:50:01.000Z');
const lateBucket = Date.parse('2026-10-13T09:50:02.000Z');

async function main() {
  const fixture = await startFixture();
  let browser;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, bypassCSP: true });
    const page = await context.newPage();
    const pageErrors = [];
    let cancelRequested = false;
    let delayJobs = false;
    let sentInitialStatus = false;
    let reconnected = false;
    let eventConnectionCount = 0;
    let resolveCancellation;
    let resolveResume;
    const cancellationRequested = new Promise(resolve => { resolveCancellation = resolve; });
    const resumed = new Promise(resolve => { resolveResume = resolve; });
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.addInitScript(() => {
      const NativeEventSource = window.EventSource;
      const instances = [];
      class TrackedEventSource extends NativeEventSource {
        constructor(url, configuration) {
          super(url, configuration);
          instances.push(this);
        }
      }
      Object.defineProperty(window, '__attEventSourceInstances', { value: instances, configurable: true });
      window.EventSource = TrackedEventSource;
    });
    page.on('dialog', async dialog => {
      assert.match(dialog.message(), /Start .* Load|Cancel job/i);
      await dialog.accept();
    });

    await page.route('**/api/v1/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      const resource = decodeURIComponent(url.pathname.split('/api/v1/')[1] || '').replace(/^\/+/, '');
      const method = request.method();
      const json = (body, status = 200) => route.fulfill({
        status, contentType: 'application/json; charset=utf-8', body: JSON.stringify(body),
      });

      if (method === 'GET' && resource === 'version') return json({ apiVersion: '1', inlineLoadEnabled: true });
      if (method === 'GET' && resource === 'packages') return json({ items: [{ packageId: 'payments', name: 'Payments' }] });
      if (method === 'GET' && resource === 'packages/payments/configuration') return json({
        view: 'declared', environments: [{ name: 'SIT' }, { name: 'UAT' }],
      });
      if (method === 'GET' && resource === 'jobs') {
        if (delayJobs) await new Promise(resolve => setTimeout(resolve, 700));
        return json({ items: [job] });
      }
      if (method === 'GET' && resource === 'packages/payments/load-policy') return json({
        model: url.searchParams.get('model') || 'virtualUsers',
        policy: { schemaVersion: 'att-load/v1.6', load: { warmup: '1s', rampUp: '2s', duration: '30s', rampDown: '1s', users: 12 } },
        redacted: false,
      });
      if (method === 'GET' && resource === 'packages/payments/resources') return json({ items: [
        { type: 'template', resourceId: 'checkout', logicalId: 'checkout', state: 'ready' },
      ], total: 1, nextCursor: null });
      if (method === 'GET' && resource === 'packages/payments/resources/template/checkout/quick-load-form') return json({
        target: { type: 'template', id: 'checkout' }, model: url.searchParams.get('model') || 'virtualUsers',
        input: { inputs: { accountId: 'acct-204' }, vars: { token: { $attDebugKeepDefault: 'hidden' } } }, redacted: true,
      });
      if (method === 'POST' && resource === 'drafts/load') return json({
        draftId: 'LOAD-DRAFT-204', packageId: 'payments', model: 'virtualUsers',
        preview: { status: 'PASS' }, previewYaml: 'schemaVersion: att-load/v1.6\n', redacted: false,
        diagnostics: [], expiresAt: new Date(Date.now() + 60_000).toISOString(),
      }, 201);
      if (method === 'POST' && resource === 'jobs/load') return json({ jobId, status: 'RUNNING', command: 'load', packageId: 'payments' }, 202);
      if (method === 'GET' && resource === `jobs/${jobId}`) return json({ ...job, status: cancelRequested ? 'CANCELLED' : 'RUNNING' });
      if (method === 'GET' && resource === `jobs/${jobId}/result`) return json({
        job, result: { status: 'PASS', metrics: { completedThroughput: 120, p95Ms: 85, buckets: {
          [String(lateBucket)]: { bucketStartEpochMs: lateBucket, bucketStart: new Date(lateBucket).toISOString(), completedTps: 120, p95Ms: 85, p99Ms: 110 },
          [String(earlyBucket)]: { bucketStartEpochMs: earlyBucket, bucketStart: new Date(earlyBucket).toISOString(), completedTps: 96, p95Ms: 74, p99Ms: 99 },
        } } },
      });
      if (method === 'GET' && resource === `jobs/${jobId}/artifacts`) return json({ items: [{ path: 'load-summary.json', size: 512 }], primaryReports: [], archiveStatus: 'READY' });
      if (method === 'GET' && resource === `jobs/${jobId}/events`) {
        eventConnectionCount += 1;
        if (!sentInitialStatus) {
          sentInitialStatus = true;
          return route.fulfill({
            status: 200,
            contentType: 'text/event-stream; charset=utf-8',
            body: 'id: 1\nevent: status\ndata: {"status":"RUNNING","console":"[LOAD] status=RUNNING jobId=JOB-LOAD-204"}\nretry: 50\n\n',
          });
        }
        if (eventConnectionCount > 1) {
          reconnected = true;
          resolveResume();
        }
        await cancellationRequested;
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream; charset=utf-8',
          body: 'id: 2\nevent: status\ndata: {"status":"CANCELLED"}\n\nid: 3\nevent: result\ndata: {"status":"CANCELLED"}\n\n',
        });
      }
      if (method === 'DELETE' && resource === `jobs/${jobId}`) {
        cancelRequested = true;
        resolveCancellation();
        return json({ jobId, status: 'CANCEL_REQUESTED' }, 202);
      }
      return json({ error: { code: 'UNEXPECTED_FIXTURE_REQUEST', summary: `${method} ${resource}` } }, 404);
    });

    const base = `${fixture.origin}/att/ui/`;
    await page.goto(`${base}#/advanced-load/payments`);
    await page.getByRole('heading', { name: 'New Load scenario' }).waitFor();
    await page.getByText(/Safe policy defaults loaded|Policy defaults loaded/i).waitFor();
    await page.getByLabel('Target logical ID', { exact: true }).fill('checkout');
    await page.getByRole('button', { name: 'Load sidecar defaults' }).click();
    await page.getByRole('status').filter({ hasText: 'Safe defaults loaded; hidden sensitive values remain protected.' }).waitFor();
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    await assertAxe(page, 'Advanced Load should pass the configured WCAG accessibility rules');

    const sectionNav = page.getByRole('navigation', { name: 'Load scenario sections' });
    for (const [name, sectionId] of [
      ['Workloads', 'load-workloads'], ['Thresholds and evidence', 'load-thresholds'], ['Review', 'load-review'],
    ]) {
      await sectionNav.getByRole('button', { name }).click();
      assert.equal(await page.evaluate(() => document.activeElement?.id), sectionId, `${name} navigation should focus its section`);
    }

    await page.getByRole('button', { name: 'Validate scenario' }).click();
    await page.getByRole('status').filter({ hasText: 'Server validation passed.' }).waitFor({ timeout: 10_000 });
    await page.getByRole('button', { name: 'Review and start Load' }).click();
    await page.waitForURL(new RegExp(`#\\/jobs\\/${jobId}$`));
    await page.getByRole('heading', { name: `Job ${jobId}` }).waitFor();
    await assertAxe(page, 'Loaded Job detail should pass the configured WCAG accessibility rules');
    await Promise.race([resumed, new Promise((_, reject) => setTimeout(() => reject(new Error('EventSource did not reconnect after the stream closed')), 5000))]);
    assert.equal(reconnected, true, 'EventSource should retry the stream after a connection ends');
    assert.equal(await openEventSourceCount(page), 1, 'Job Details should have exactly one open EventSource');

    await page.getByRole('button', { name: 'Open floating console' }).click();
    const floating = page.locator(`[aria-label="Floating console for Job ${jobId}"]`);
    await floating.waitFor();
    await floating.getByRole('heading', { name: `Job ${jobId}`, exact: true }).waitFor();
    await floating.getByText('[LOAD] status=RUNNING jobId=JOB-LOAD-204', { exact: true }).waitFor();
    assert.equal(await openEventSourceCount(page), 1, 'Job Details and the floating console should share exactly one open EventSource');
    await floating.getByRole('button', { name: 'Maximize Job console' }).click();
    const maximizedFloating = page.locator(`[aria-label="Floating console for Job ${jobId}"]`);
    const restoreConsole = maximizedFloating.getByRole('button', { name: 'Restore Job console' });
    const minimizeConsole = maximizedFloating.getByRole('button', { name: 'Minimize Job console' });
    const closeConsole = maximizedFloating.getByRole('button', { name: 'Close Job console' });
    assert.equal(await maximizedFloating.evaluate(element => element.classList.contains('is-maximized')), true, 'Maximize should expand the floating console panel');
    await assertPointerReachable(page, restoreConsole, 'Restore should receive pointer input above maximized output');
    await assertPointerReachable(page, minimizeConsole, 'Minimize should receive pointer input above maximized output');
    await assertPointerReachable(page, closeConsole, 'Close should receive pointer input above maximized output');
    const maximizedLiveTab = maximizedFloating.getByRole('tab', { name: 'Live Output', exact: true });
    const maximizedEventsTab = maximizedFloating.getByRole('tab', { name: 'Events', exact: true });
    assert.equal(await maximizedLiveTab.isVisible(), true, 'Live Output view switch should remain visible while maximized');
    assert.equal(await maximizedEventsTab.isVisible(), true, 'Events view switch should remain visible while maximized');
    await maximizedLiveTab.focus();
    await page.keyboard.press('ArrowRight');
    assert.equal(await page.evaluate(() => document.activeElement?.id), await maximizedEventsTab.getAttribute('id'), 'view switch should remain keyboard reachable while maximized');
    await maximizedLiveTab.click();
    assert.equal(await maximizedLiveTab.getAttribute('aria-selected'), 'true', 'Live Output should be selectable again while maximized');
    await restoreConsole.click();
    assert.equal(await maximizedFloating.evaluate(element => element.classList.contains('is-maximized')), false, 'Restore should return the floating console to its normal size');
    await assertAxe(page, 'Job Details with the floating console open should pass the configured WCAG accessibility rules');
    await page.evaluate(() => { window.location.hash = '#/packages'; });
    await page.waitForURL(/#\/packages/);
    const floatingOnExplorer = page.locator(`[aria-label="Floating console for Job ${jobId}"]`);
    assert.equal(await floatingOnExplorer.count(), 1, 'floating console should survive a Studio route change');
    await floatingOnExplorer.getByRole('heading', { name: `Job ${jobId}`, exact: true }).waitFor();
    await floatingOnExplorer.getByText('[LOAD] status=RUNNING jobId=JOB-LOAD-204', { exact: true }).waitFor();
    await page.goto(`${base}#/jobs/${jobId}`);
    await page.getByRole('heading', { name: `Job ${jobId}`, level: 1, exact: true }).waitFor();
    const floatingAfterRoute = page.locator(`[aria-label="Floating console for Job ${jobId}"]`);
    await floatingAfterRoute.getByRole('button', { name: 'Minimize Job console' }).click();
    await page.getByRole('button', { name: `Restore console for Job ${jobId}` }).waitFor();
    assert.equal(await page.locator('.att-console-minimized-host .att-job-events').count(), 0, 'minimized console should not render hidden event rows');
    await page.getByRole('button', { name: `Restore console for Job ${jobId}` }).click();
    const floatingAfterRestore = page.locator(`[aria-label="Floating console for Job ${jobId}"]`);
    await floatingAfterRestore.getByRole('heading', { name: `Job ${jobId}`, exact: true }).waitFor();
    const liveTab = floatingAfterRestore.getByRole('tab', { name: 'Live Output', exact: true });
    assert.equal(await liveTab.getAttribute('aria-selected'), 'true', 'restoring the console should keep its selected Live Output view');
    await floatingAfterRestore.getByText('[LOAD] status=RUNNING jobId=JOB-LOAD-204', { exact: true }).waitFor();
    const newTab = floatingAfterRestore.getByRole('link', { name: 'Open Job console in a new tab' });
    assert.equal(await newTab.getAttribute('target'), '_blank');
    assert.match(await newTab.getAttribute('rel') || '', /noopener/);
    assert.match(await newTab.getAttribute('href') || '', new RegExp(`#\\/jobs\\/${jobId}\\/console\\?view=live`));
    await floatingAfterRestore.getByRole('button', { name: 'Close Job console' }).click();
    assert.equal(await page.locator(`[aria-label="Floating console for Job ${jobId}"]`).count(), 0, 'Close should remove the floating console');
    assert.equal(cancelRequested, false, 'closing the floating console must not cancel the Job');
    assert.equal(await openEventSourceCount(page), 1, 'Job Details should keep exactly one open EventSource after the floating console closes');

    const tabs = [
      ['Overview', 'Status'], ['Live Output', '[LOAD] status=RUNNING jobId=JOB-LOAD-204'], ['Events', null],
      ['Results', 'Results'], ['Artifacts', 'Artifacts'], ['Metrics', 'Metrics'],
    ];
    for (const [name, content] of tabs) {
      const tab = page.getByRole('tab', { name, exact: true });
      await tab.click();
      const panelId = await tab.getAttribute('aria-controls');
      assert.ok(panelId, `${name} tab should point to its stable panel`);
      const panel = page.locator(`#${panelId}`);
      assert.equal(await panel.isVisible(), true, `${name} panel should be visible`);
      if (content === null) {
        const eventList = panel.getByRole('list', { name: 'Structured job events' });
        assert.equal(await eventList.count(), 1, 'Events panel should render the shared structured event list');
        assert.equal(await eventList.getByRole('listitem').count() > 0, true, 'Events panel should show the retained status event');
      } else {
        assert.equal(await panel.getByText(content, { exact: true }).count() > 0, true, `${name} panel should show ${content}`);
      }
    }
    await page.getByRole('tab', { name: 'Metrics' }).click();
    const metricsPanel = page.locator('#job-panel-metrics');
    await metricsPanel.getByRole('img', { name: /Completed throughput by bucket start time/i }).waitFor();
    assert.equal(await metricsPanel.locator('svg[role="img"]').count(), 3, 'the returned throughput and latency bucket series should be charted');
    await assertAxe(page, 'Load metric charts and their data table should pass the configured WCAG accessibility rules');
    const bucketTimes = await metricsPanel.locator('[aria-label="Returned Load time-series buckets"] tbody time').evaluateAll(nodes => nodes.map(node => node.getAttribute('datetime')));
    assert.deepEqual(bucketTimes, [new Date(earlyBucket).toISOString(), new Date(lateBucket).toISOString()], 'map-shaped server buckets should be ordered by actual start time');

    await page.getByRole('tab', { name: 'Results' }).click();
    await page.getByText(/Server result returned/i).waitFor();
    await page.getByRole('tab', { name: 'Artifacts' }).click();
    await page.getByRole('status').filter({ hasText: 'Artifact Archive: READY' }).waitFor();
    await page.getByRole('button', { name: 'Preview: load-summary.json', exact: true }).waitFor();

    await page.getByRole('button', { name: 'Cancel job' }).click();
    await page.waitForFunction(() => document.querySelector('.att-job-actions .att-status-badge')?.textContent?.trim() === 'CANCELLED', null, { timeout: 10_000 });
    assert.equal(await page.getByRole('button', { name: 'Cancel job' }).count(), 0, 'terminal status should remove the cancel action');

    await page.goto(`${base}#/jobs/${jobId}/console?view=events`);
    await page.reload();
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    const standaloneEventsTab = page.getByRole('tab', { name: 'Events', exact: true });
    await standaloneEventsTab.waitFor();
    assert.equal(await standaloneEventsTab.getAttribute('aria-selected'), 'true', 'standalone console reload should preserve the Events view');
    await standaloneEventsTab.page().locator('.att-job-events').first().waitFor();
    assert.equal(await createdEventSourceCount(page), 1, 'the standalone console tab should create exactly one EventSource for the terminal Job replay');
    await assertAxe(page, 'Standalone Job console should pass the configured WCAG accessibility rules');
    assert.equal(await page.getByRole('link', { name: 'Open full Job details' }).count(), 0, 'standalone console must keep the fixed Job without a details navigation action');
    await page.goto(`${base}#/jobs/${jobId}`);
    await page.waitForURL(new RegExp(`#\\/jobs\\/${jobId}$`));
    await page.getByRole('heading', { name: `Job ${jobId}`, level: 1, exact: true }).waitFor();

    await page.goto(`${base}#/jobs`);
    await page.getByRole('heading', { name: 'Jobs' }).waitFor();
    await assertAxe(page, 'Jobs list should pass the configured WCAG accessibility rules');
    const statusFilter = page.locator('.att-jobs-filter-panel select').first();
    await statusFilter.selectOption('RUNNING');
    delayJobs = true;
    await page.getByRole('button', { name: 'Refresh jobs' }).click();
    await page.getByText(/Refreshing .* loaded jobs/i).waitFor();
    assert.equal(await statusFilter.inputValue(), 'RUNNING', 'refresh should preserve the selected filter while the response is pending');
    await page.getByText(/at most 100 jobs/i).waitFor();
    await page.getByText(jobId, { exact: true }).waitFor({ timeout: 10_000 });
    assert.equal(await statusFilter.inputValue(), 'RUNNING');

    assert.deepEqual(pageErrors, [], 'Load/Jobs UI should not report uncaught browser errors');
    console.log('ATT Load/Jobs browser acceptance passed.');
    await context.close();
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

async function assertAxe(page, message) {
  const audit = await page.evaluate(async () => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  }));
  assert.deepEqual(audit.violations.map(({ id, impact, help, nodes }) => ({
    id, impact, help, selectors: nodes.map(node => node.target),
  })), [], message);
}

async function assertPointerReachable(page, locator, message) {
  assert.equal(await locator.isVisible(), true, message);
  assert.equal(await locator.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    const target = document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2);
    return target === element || element.contains(target);
  }), true, message);
}

async function createdEventSourceCount(page) {
  return page.evaluate(() => (window.__attEventSourceInstances || []).length);
}

async function openEventSourceCount(page) {
  return page.evaluate(() => (window.__attEventSourceInstances || [])
    .filter(source => source.readyState !== window.EventSource.CLOSED).length);
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
