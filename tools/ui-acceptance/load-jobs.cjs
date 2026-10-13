const assert = require('node:assert/strict');
const { launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

const jobId = 'JOB-LOAD-204';
const job = {
  jobId, packageId: 'payments', command: 'load', principal: 'qa-smoke', status: 'RUNNING',
  createdAt: '2026-10-13T09:50:00Z', startedAt: '2026-10-13T09:50:01Z',
  performance: { timings: { queueMs: 4, executionMs: 950 }, worker: { heapUsedBytes: 2048 } },
};

async function main() {
  const fixture = await startFixture();
  let browser;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    const pageErrors = [];
    let cancelRequested = false;
    let delayJobs = false;
    let sentInitialStatus = false;
    let resolveCancellation;
    const cancellationRequested = new Promise(resolve => { resolveCancellation = resolve; });
    page.on('pageerror', error => pageErrors.push(error.message));
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
        { type: 'template', logicalId: 'checkout', state: 'ready' },
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
        job, result: { status: 'PASS', metrics: { completedThroughput: 120, p95Ms: 85, buckets: [{ second: 1, requests: 120 }] } },
      });
      if (method === 'GET' && resource === `jobs/${jobId}/artifacts`) return json({ items: [{ path: 'load-summary.json', size: 512 }] });
      if (method === 'GET' && resource === `jobs/${jobId}/events`) {
        if (!sentInitialStatus) {
          sentInitialStatus = true;
          return route.fulfill({
            status: 200,
            contentType: 'text/event-stream; charset=utf-8',
            body: 'id: 1\nevent: status\ndata: {"status":"RUNNING"}\nretry: 50\n\n',
          });
        }
        await cancellationRequested;
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream; charset=utf-8',
          body: 'id: 2\nevent: status\ndata: {"status":"CANCELLED"}\n\n',
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
    await page.locator('.att-target-editor label').filter({ hasText: 'Target logical ID' }).getByRole('textbox').fill('checkout');
    await page.getByRole('button', { name: 'Load sidecar defaults' }).click();
    await page.getByText(/Safe .*defaults loaded/i).waitFor();

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

    const tabs = [
      ['Overview', 'Status'], ['Live logs', 'Live logs'], ['Events', 'Events'],
      ['Results', 'Results'], ['Artifacts', 'Artifacts'], ['Metrics', 'Metrics'],
    ];
    for (const [name, content] of tabs) {
      const tab = page.getByRole('tab', { name, exact: true });
      await tab.click();
      const panelId = await tab.getAttribute('aria-controls');
      assert.ok(panelId, `${name} tab should point to its stable panel`);
      const panel = page.locator(`#${panelId}`);
      assert.equal(await panel.isVisible(), true, `${name} panel should be visible`);
      assert.equal(await panel.getByText(content, { exact: true }).count() > 0, true, `${name} panel should show ${content}`);
    }
    await page.getByRole('tab', { name: 'Results' }).click();
    await page.getByText(/Server result returned/i).waitFor();
    await page.getByRole('tab', { name: 'Artifacts' }).click();
    await page.getByRole('link', { name: /load-summary\.json/ }).waitFor();

    await page.getByRole('button', { name: 'Cancel job' }).click();
    await page.getByText('CANCELLED', { exact: true }).waitFor({ timeout: 10_000 });
    assert.equal(await page.getByRole('button', { name: 'Cancel job' }).count(), 0, 'terminal status should remove the cancel action');

    await page.goto(`${base}#/jobs`);
    await page.getByRole('heading', { name: 'Jobs' }).waitFor();
    const statusFilter = page.getByLabel('Status', { exact: true });
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

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
