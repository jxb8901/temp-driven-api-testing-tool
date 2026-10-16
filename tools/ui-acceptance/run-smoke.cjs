const assert = require('node:assert/strict');
const { chromium, launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture();
  const browser = await launchBrowser(chromium);
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, bypassCSP: true });
    context.setDefaultTimeout(10000);
    const definitions = []; const submissions = [];
    await context.route('**/api/v1/version', route => route.fulfill({ json: { version: '4.2.1', apiVersion: '1', inlineLoadEnabled: true, loadSchedulingEnabled: true, unifiedRunVersion: 'att-execution/v1' } }));
    await context.route('**/api/v1/drafts/run', async route => {
      definitions.push(route.request().postDataJSON());
      await route.fulfill({ status: 201, json: { draftId: 'R0123456789ABCDEF0123456789ABCDEF', executionType: 'run', expiresAt: new Date(Date.now() + 600000).toISOString(), preview: { selectedCaseIds: ['cases.default.C1'] } } });
    });
    await context.route('**/api/v1/jobs/run', async route => {
      submissions.push(route.request().postDataJSON());
      await route.fulfill({ status: 202, json: { jobId: 'JOB-204', command: 'run', executionType: 'run', packageId: 'payments', status: 'QUEUED' } });
    });
    const page = await context.newPage(); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${fixture.origin}/att/ui/#/run/payments`);
    await page.getByRole('heading', { name: 'Run', exact: true }).waitFor();
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    assert.deepEqual(await page.evaluate(async () => (await window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag22aa'] } })).violations.map(item => item.id)), []);
    await page.getByLabel('Environment', { exact: true }).fill('SIT');
    await page.getByLabel('Target ID', { exact: true }).fill('cases.default.C1');
    assert.equal(definitions.length, 0); assert.equal(submissions.length, 0);
    await page.getByRole('button', { name: 'Validate', exact: true }).click();
    await page.getByRole('heading', { name: 'Review', exact: true }).waitFor();
    assert.deepEqual(definitions[0].target, { type: 'case', id: 'cases.default.C1' });
    assert.equal(definitions[0].environment, 'SIT'); assert.equal(definitions[0].options.debug, false);
    await page.getByLabel('Environment', { exact: true }).fill('UAT');
    assert.equal(await page.getByRole('heading', { name: 'Review', exact: true }).count(), 0);
    await page.getByLabel('Debug diagnostics', { exact: true }).check();
    await page.getByRole('button', { name: 'Validate', exact: true }).click();
    await page.getByRole('heading', { name: 'Review', exact: true }).waitFor();
    assert.equal(definitions.at(-1).environment, 'UAT'); assert.equal(definitions.at(-1).options.debug, true);
    assert.equal(submissions.length, 0);
    await page.getByRole('button', { name: 'Schedule…', exact: true }).click();
    await page.getByRole('dialog').waitFor();
    assert.equal(submissions.length, 0, 'opening the schedule form must not execute RUN');
    await page.getByRole('button', { name: 'Cancel and return to editor', exact: true }).click();
    await page.getByRole('button', { name: 'Run Now', exact: true }).click();
    try { await page.waitForURL('**/#/jobs/JOB-204'); } catch (error) { console.error(JSON.stringify({ url: page.url(), submissions, visible: await page.locator('body').innerText() })); throw error; }
    assert.equal(submissions.length, 1);
    assert.deepEqual(submissions[0], { packageId: 'payments', draftId: 'R0123456789ABCDEF0123456789ABCDEF' });
    await page.goto(`${fixture.origin}/att/ui/#/run/payments`);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('heading', { name: 'Run', exact: true }).waitFor();
    try { await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth); } catch (error) { console.error(JSON.stringify(await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth, overflow: [...document.querySelectorAll('main *')].filter(e => e.getBoundingClientRect().right > innerWidth).map(e => ({ tag: e.tagName, cls: e.className, width: e.getBoundingClientRect().width, right: e.getBoundingClientRect().right })).slice(0, 12) })))); throw error; }
    assert.deepEqual(errors, []);
    await context.close();
    console.log('ATT RUN browser acceptance passed: validation/review, SIT/UAT, diagnostic invalidation, schedule without execution, single submit, mobile layout and accessibility.');
  } finally { await browser.close(); await stopFixture(fixture); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
