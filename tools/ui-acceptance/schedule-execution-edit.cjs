const assert = require('node:assert/strict');
const { chromium, launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture();
  const browser = await launchBrowser(chromium);
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, bypassCSP: true });
    context.setDefaultTimeout(15000);
    const common = {
      packageId: 'payments', environment: 'SIT', timezone: 'Asia/Hong_Kong', cron: '0 2 * * *',
      nextRunAt: '2026-11-02T18:00:00Z', enabled: true, maxConcurrentRuns: 1, inlineSnapshot: true,
      definitionVersion: 4, snapshotVersion: 2,
    };
    let schedules = [{
      ...common, id: 'S-LOAD', name: 'Nightly load', executionType: 'load', loadModel: 'arrival-rate', createdBy: 'alice',
      targetIdentity: { workloads: [{ name: 'payments', model: 'arrivalRate', arrivalRate: '100/s', maxConcurrent: 20, targets: [], mix: false }] },
    }];
    const mutations = [];
    await context.route('**/api/v1/version', route => route.fulfill({ json: {
      version: '4.2.8', apiVersion: '1', inlineLoadEnabled: true, loadSchedulingEnabled: true, unifiedRunVersion: 'att-execution/v1',
    } }));
    await context.route('**/api/v1/schedules**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname.split('/schedules')[1] || '';
      const method = request.method();
      if (method !== 'GET') mutations.push({ method, path, body: request.postDataJSON() });
      if (path === '/preview') return route.fulfill({ json: { nextRunAt: common.nextRunAt, nextOccurrences: [common.nextRunAt] } });
      if (path.includes('/executions') && !path.includes('execution')) return route.fulfill({ json: { items: [] } });
      if (path.endsWith('/execution-draft') && method === 'POST') {
        const body = request.postDataJSON();
        assert.equal(body.expectedDefinitionVersion, 4);
        assert.equal(body.overrides.environment, 'UAT');
        assert.deepEqual(body.overrides.thresholds, { passRate: 0.99 });
        assert.equal(body.overrides.workloads[0].load.arrivalRate, '150/s');
        return route.fulfill({ json: {
          draftId: 'A-EXEC', definitionVersion: 4, snapshotVersion: 2, revisionDigest: 'abc', sourcePolicy: 'strict',
          expiresAt: new Date(Date.now() + 120000).toISOString(), nextRunAt: common.nextRunAt,
          originalPreview: { previewYaml: 'arrivalRate: 100/s\nenvironment: SIT' },
          preview: { previewYaml: 'arrivalRate: 150/s\nenvironment: UAT' },
        } });
      }
      if (path.endsWith('/execution') && method === 'PATCH') {
        const body = request.postDataJSON();
        assert.equal(body.draftId, 'A-EXEC');
        assert.equal(body.confirm, true);
        schedules = schedules.map(item => item.id === 'S-LOAD' ? { ...item, snapshotVersion: 3, environment: 'UAT', definitionVersion: 5,
          targetIdentity: { workloads: [{ name: 'payments', model: 'arrivalRate', arrivalRate: '150/s', maxConcurrent: 20, targets: [], mix: false }] } } : item);
        return route.fulfill({ json: schedules[0] });
      }
      if (method === 'GET') return route.fulfill({ json: { items: schedules } });
      if (method === 'PATCH') {
        const id = path.slice(1);
        schedules = schedules.map(item => item.id === id ? { ...item, ...request.postDataJSON() } : item);
        return route.fulfill({ json: schedules.find(item => item.id === id) });
      }
      throw new Error(`Unexpected schedule request: ${method} ${path}`);
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${fixture.origin}/att/ui/#/schedules`);
    await page.getByText('1 of 1 schedules shown.').waitFor();
    await page.getByRole('button', { name: 'Edit Nightly load', exact: true }).first().click();
    await page.waitForURL('**/#/schedules/S-LOAD/edit');
    await page.getByRole('button', { name: 'Edit execution settings', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit execution settings' });
    await dialog.waitFor();
    await dialog.getByLabel('Arrival rate').fill('150/s');
    await dialog.getByLabel('Environment', { exact: true }).fill('UAT');
    await dialog.getByLabel('Thresholds JSON').fill('{"passRate":0.99}');
    await dialog.getByRole('button', { name: 'Validate changes', exact: true }).click();
    await dialog.getByText('Review future snapshot 3').waitFor();
    assert.match(await dialog.innerText(), /− Before: arrivalRate: 100\/s/);
    assert.match(await dialog.innerText(), /\+ After: arrivalRate: 150\/s/);
    await dialog.getByRole('button', { name: 'Save as new snapshot', exact: true }).click();
    await page.getByText('Execution snapshot updated for future occurrences.').waitFor();
    assert.equal(schedules[0].snapshotVersion, 3);
    assert.equal(schedules[0].environment, 'UAT');
    assert.ok(mutations.some(item => item.path.endsWith('/execution-draft')));
    assert.ok(mutations.some(item => item.path.endsWith('/execution') && item.method === 'PATCH'));
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
