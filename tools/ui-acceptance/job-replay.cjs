const assert = require('node:assert/strict');
const { launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture(); const browser = await launchBrowser();
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    context.setDefaultTimeout(15000);
    const jobs = new Map(['run', 'load'].map(type => ['J-' + type, { jobId: 'J-' + type, command: type, executionType: type, packageId: 'payments', principal: 'alice', status: 'PASS' }]));
    jobs.set('J-old', { jobId: 'J-old', command: 'load', packageId: 'payments', status: 'PASS' });
    const submits = [], saves = []; let schedules = []; let sequence = 0;
    const nextRunAt = '2099-01-01T02:00:00Z';
    await context.route('**/api/v1/**', async route => {
      const request = route.request(), method = request.method(), url = new URL(request.url());
      const path = decodeURIComponent(url.pathname.split('/api/v1/')[1] || '');
      const json = (body, status = 200) => route.fulfill({ status, json: body });
      if (path === 'version') return json({ version: '4.2.3', apiVersion: '1', inlineLoadEnabled: true, loadSchedulingEnabled: true, unifiedRunVersion: 'att-execution/v1' });
      if (path === 'packages') return json({ items: [{ packageId: 'payments', name: 'Payments' }] });
      if (path === 'packages/payments/configuration') return json({ view: 'declared', environments: [{ name: 'SIT' }] });
      if (path === 'packages/payments/load-catalog') return json({ items: [], total: 0 });
      if (path === 'jobs') return json({ items: [...jobs.values()].filter(job => !url.searchParams.has('executionType') || job.command === 'load') });
      const match = /^jobs\/([^/]+)(?:\/(.*))?$/.exec(path);
      if (match) {
        const [, id, action] = match, job = jobs.get(id);
        if (!job) return json({ message: 'Unavailable' }, 404);
        const definition = { executionType: job.executionType || 'load', packageId: 'payments', environment: 'SIT', ...(job.command === 'run' ? { target: { type: 'tool', id: 'version' }, input: {}, options: { debug: true } } : { scenario: { schemaVersion: 'att-load/v1.6', workloads: [{ id: 'smoke', target: { type: 'template', id: 'SIMPLE' }, load: { users: 1, duration: '1s' } }] } }) };
        if (action === 'replay' || action === 'replay-draft') return json(id === 'J-old' ? { jobId: id, available: false, reason: 'Rerun unavailable for this historical job' } : { jobId: id, available: true, definition, revisionPolicy: 'reject-package-changes', intentId: 'I-' + id, draftId: 'D-' + id, expiresAt: '2099-01-01T00:00:00Z' }, method === 'POST' ? 201 : 200);
        if (action === 'rerun') {
          submits.push(request.postDataJSON()); const created = { ...job, jobId: 'J-new-' + (++sequence), rerunOfJobId: id }; jobs.set(created.jobId, created); return json(created, 202);
        }
        if (action === 'result') return json({ job, result: { status: 'PASS' } });
        if (action === 'events') return route.fulfill({ contentType: 'text/event-stream', body: 'event: status\ndata: {"status":"PASS"}\n\n' });
        if (action === 'artifacts') return json({ items: [] });
        if (!action) return json(job);
      }
      if (path === 'schedules/preview') return json({ nextRunAt, nextOccurrences: [nextRunAt] });
      if (path === 'schedules' && method === 'POST') {
        const body = request.postDataJSON(); saves.push(body); const created = { ...body, id: 'S-' + saves.length, nextRunAt, inlineSnapshot: true }; schedules.push(created); return json(created, 201);
      }
      if (path === 'schedules') return json({ items: schedules });
      if (/^schedules\/[^/]+\/executions$/.test(path)) return json({ items: [{ occurrenceId: 'O1', scheduledFor: nextRunAt, triggerType: 'manual', status: 'submitted', jobId: 'J-load', jobStatus: 'PASS' }] });
      if (/^schedules\/[^/]+\/trigger$/.test(path)) return json({ occurrenceId: 'O1', jobId: 'J-load', status: 'submitted' }, 202);
      return route.fallback();
    });
    const page = await context.newPage(), errors = []; page.on('pageerror', error => errors.push(error.message));
    const base = fixture.origin + '/att/ui/';
    for (const type of ['run', 'load']) {
      await page.goto(base + '#/jobs');
      const row = page.getByRole('row').filter({ has: page.getByRole('button', { name: 'J-' + type, exact: true }) });
      await row.getByRole('button', { name: 'Rerun', exact: true }).click();
      const review = page.getByRole('region', { name: 'Review rerun J-' + type });
      await review.getByRole('button', { name: 'Confirm rerun', exact: true }).waitFor({ state: 'visible' });
      assert.equal(submits.length, type === 'run' ? 0 : 1);
      await review.getByRole('button', { name: 'Confirm rerun', exact: true }).click();
      await page.waitForURL(url => url.hash.includes('/jobs/J-new-'));
      await page.getByRole('heading', { name: /Job J-new-/ }).waitFor();
      await page.getByRole('button', { name: 'Schedule / Reschedule…', exact: true }).click();
      await page.getByRole('button', { name: 'Continue to New Schedule', exact: true }).click();
      await page.waitForURL(url => url.hash.startsWith('#/schedules/new'));
      await page.getByRole('heading', { name: 'New Schedule', exact: true }).waitFor();
      await page.locator('#schedule-name').fill(type + ' from job');
      await page.getByText(/^Next run:/).waitFor();
      await page.getByRole('button', { name: 'Save schedule', exact: true }).click();
      await page.waitForURL(url => url.hash === '#/schedules');
      assert.equal(saves.at(-1).executionType, type); assert.ok(saves.at(-1).draftId);
      await page.getByRole('button', { name: 'Run Now ' + type + ' from job', exact: true }).first().click();
      await page.getByRole('button', { name: 'Confirm Run Now', exact: true }).click();
      await page.waitForURL(url => /#\/schedules\/S-\d+\/history/.test(url.hash));
      await page.getByRole('button', { name: 'J-load', exact: true }).click();
      await page.waitForURL(url => url.hash === '#/jobs/J-load');
    }
    await page.goto(base + '#/advanced-load');
    const history = page.locator('.att-load-job-row').filter({ has: page.getByRole('button', { name: 'J-load', exact: true }) });
    await history.getByRole('button', { name: 'Rerun', exact: true }).click();
    await page.getByRole('region', { name: 'Review rerun J-load' }).getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.goto(base + '#/jobs/J-old');
    await page.getByRole('button', { name: 'Rerun', exact: true }).click();
    await page.getByText('Rerun unavailable for this historical job', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Confirm rerun', exact: true }).isDisabled(), true);
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + '#/jobs/J-load');
    await page.getByRole('button', { name: 'Rerun', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm rerun', exact: true }).and(page.locator(':enabled')).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Review must fit a phone viewport');
    await page.getByRole('button', { name: 'Jobs and results', exact: true }).click();
    await page.getByRole('dialog', { name: 'Discard your changes?' }).waitFor();
    await page.getByRole('button', { name: 'Stay on this page', exact: true }).click();
    await page.getByRole('dialog', { name: 'Discard your changes?' }).waitFor({ state: 'hidden' });
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('region', { name: 'Review rerun J-load' }).count(), 0);
    assert.deepEqual(errors, []); await context.close();
    console.log('Job replay acceptance passed: RUN/LOAD list → review → independent Job → unified Schedule, historical fallback, mobile and keyboard Cancel.');
  } finally { await browser.close(); await stopFixture(fixture); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
