const assert = require('node:assert/strict');
const { chromium, launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture();
  const browser = await launchBrowser(chromium);
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, bypassCSP: true });
    context.setDefaultTimeout(15000);
    const common = { packageId: 'payments', environment: 'SIT', timezone: 'Asia/Hong_Kong', cron: '0 2 * * *',
      nextRunAt: '2026-11-02T18:00:00Z', enabled: false, maxConcurrentRuns: 1, inlineSnapshot: true };
    let schedules = [
      { ...common, id: 'S-RUN', name: 'Functional checks', executionType: 'run', selectedCaseIds: ['suite.C1', 'suite.C3'], createdBy: 'alice' },
      { ...common, id: 'S-LOAD', name: 'Performance checks', executionType: 'load', loadModel: 'arrival-rate', createdBy: 'bob' },
      { ...common, id: 'S-DEBUG', name: 'Legacy diagnostic', jobType: 'debug', debugTarget: { type: 'tool', id: 'inspect' }, createdBy: 'alice' },
    ];
    const mutations = [];
    const validations = [];
    await context.route('**/api/v1/version', route => route.fulfill({ json: {
      version: '4.2.1', apiVersion: '1', inlineLoadEnabled: true, loadSchedulingEnabled: true, unifiedRunVersion: 'att-execution/v1',
    } }));
    await context.route('**/api/v1/drafts/run', async route => {
      validations.push(route.request().postDataJSON());
      await route.fulfill({ status: 201, json: { draftId: 'R-FROZEN', preview: { selectedCaseIds: ['suite.C1', 'suite.C3'], options: { debug: true } } } });
    });
    await context.route('**/api/v1/schedules**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname.split('/schedules')[1];
      const method = request.method();
      if (method !== 'GET') mutations.push({ method, path, body: request.postDataJSON() });
      if (path === '/preview') return route.fulfill({ json: { nextRunAt: common.nextRunAt, nextOccurrences: [common.nextRunAt] } });
      if (path.includes('/executions')) return route.fulfill({ json: { items: [
        { occurrenceId: 'O1', scheduledFor: common.nextRunAt, triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-204', jobStatus: 'PASS' },
        { occurrenceId: 'O2', scheduledFor: common.nextRunAt, triggerType: 'scheduled', status: 'submitted', jobId: 'PRIVATE', jobStatus: null },
      ] } });
      if (method === 'GET') return route.fulfill({ json: { items: schedules } });
      if (method === 'PATCH') {
        const id = path.slice(1);
        schedules = schedules.map(item => item.id === id ? { ...item, ...request.postDataJSON() } : item);
        return route.fulfill({ json: schedules.find(item => item.id === id) });
      }
      if (path === '' && method === 'POST') {
        const created = { ...common, ...request.postDataJSON(), id: 'S-NEW' };
        schedules.push(created);
        return route.fulfill({ status: 201, json: created });
      }
      throw new Error(`Unexpected schedule request: ${method} ${path}`);
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const base = `${fixture.origin}/att/ui/`;
    await page.goto(`${base}#/schedules`);
    await page.getByText('3 of 3 schedules shown.').waitFor();
    assert.equal(await page.locator('#schedule-name').count(), 0);
    assert.equal(mutations.length, 0, 'landing must not preview or create');
    const filters = page.getByRole('group', { name: 'Filter schedules' });
    // Implicit labels include their option text in the accessible name, so match the label prefix.
    await filters.getByLabel('Execution type').selectOption('run');
    await page.getByText('2 of 3 schedules shown.').waitFor();
    assert.equal(await page.getByText('Performance checks', { exact: true }).count(), 0);
    await filters.getByLabel('Owner').selectOption('bob');
    await page.getByText('No schedules match these filters.').waitFor();
    await page.getByRole('button', { name: 'New Schedule', exact: true }).click();
    await page.waitForURL('**/#/schedules/new');
    await page.locator('#schedule-name').fill('Unsaved schedule');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('button', { name: 'Keep editing', exact: true }).click();
    assert.equal(await page.locator('#schedule-name').inputValue(), 'Unsaved schedule');
    await page.getByRole('button', { name: 'Back to Schedules', exact: true }).click();
    await page.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await page.waitForURL('**/#/schedules');
    await page.getByRole('button', { name: 'Edit Legacy diagnostic', exact: true }).first().click();
    await page.waitForURL('**/#/schedules/S-DEBUG/edit');
    await page.reload();
    await page.locator('#schedule-name').waitFor();
    assert.equal(await page.locator('#schedule-name').inputValue(), 'Legacy diagnostic');
    assert.equal(await page.locator('#schedule-list-heading').count(), 0);
    await page.locator('#schedule-description').fill('Metadata edit');
    await page.getByRole('button', { name: 'Save changes', exact: true }).click();
    await page.waitForURL('**/#/schedules');
    const edit = mutations.find(item => item.method === 'PATCH');
    assert.equal(edit.body.jobType, 'debug');
    assert.equal(edit.body.executionType, undefined);
    assert.equal(edit.body.draftId, undefined);
    await page.getByRole('button', { name: 'History Functional checks', exact: true }).first().click();
    await page.waitForURL('**/#/schedules/S-RUN/history');
    await page.getByRole('button', { name: 'O1', exact: true }).click();
    await page.waitForURL('**/#/schedules/S-RUN/history/O1');
    assert.equal(await page.getByRole('button', { name: 'PRIVATE', exact: true }).count(), 0);
    await page.goBack();
    await page.waitForURL('**/#/schedules/S-RUN/history');
    await page.goForward();
    await page.waitForURL('**/#/schedules/S-RUN/history/O1');
    await page.reload();
    await page.locator('tr[aria-current="true"]').waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.locator('.att-occurrence-table').isVisible(), true);
    const mobileLayout = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      viewport: window.innerWidth,
      scrollX: window.scrollX,
      overflow: [...document.querySelectorAll('body *')].map(element => ({
        tag: element.tagName.toLowerCase(),
        className: typeof element.className === 'string' ? element.className : '',
        left: Math.round(element.getBoundingClientRect().left),
        right: Math.round(element.getBoundingClientRect().right),
        width: Math.round(element.getBoundingClientRect().width),
        minWidth: getComputedStyle(element).minWidth,
      })).filter(item => item.right > window.innerWidth + 1).slice(0, 8),
    }));
    assert.ok(mobileLayout.scrollWidth <= mobileLayout.viewport, `History must fit a phone viewport: ${JSON.stringify(mobileLayout)}`);
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    assert.deepEqual(await page.evaluate(async () => (await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag22aa'] },
    })).violations.map(({ id, impact, nodes }) => ({
      id, impact, selectors: nodes.map(node => node.target),
    }))), []);
    await page.goto(`${base}#/run/payments`);
    await page.getByLabel('Target type').selectOption('batch');
    await page.getByLabel('Debug diagnostics', { exact: true }).check();
    await page.getByRole('button', { name: 'Validate', exact: true }).click();
    await page.getByRole('button', { name: 'Schedule…', exact: true }).click();
    await page.getByRole('dialog').waitFor();
    assert.match(await page.getByRole('dialog').innerText(), /Discard your changes\?/);
    await page.getByRole('button', { name: 'Discard changes and continue', exact: true }).click();
    await page.getByRole('heading', { name: 'New Schedule', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Save schedule', exact: true }).click();
    await page.locator('#schedules-title').waitFor();
    await page.getByText(/Saved .*\(ID: S-NEW\)/).waitFor();
    await page.getByRole('button', { name: 'View schedule history', exact: true }).waitFor();
    assert.match(page.url(), /#\/schedules$/);
    const created = mutations.find(item => item.method === 'POST' && item.path === '');
    assert.equal(created.body.executionType, 'run');
    assert.equal(created.body.draftId, 'R-FROZEN');
    assert.equal(created.body.enabled, false);
    assert.equal(created.body.load, undefined);
    assert.deepEqual(validations[0].options, { debug: true });
    assert.deepEqual(validations[0].selection, { all: true });
    assert.equal(mutations.filter(item => item.path.endsWith('/trigger')).length, 0);
    assert.deepEqual(errors, []);
    await context.close();
    console.log('Unified Schedules acceptance passed: list-first, RUN/LOAD/owner filters, New/Edit/Cancel, legacy Debug adapter, deep-link refresh/back/forward, mobile History, access-safe Job links, RUN draft creation without execution.');
  } finally { await browser.close(); await stopFixture(fixture); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
