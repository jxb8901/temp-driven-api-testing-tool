const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

// Stateful HTTP fixture exercises the real browser UI; persistence and worker behavior
// are independently covered by Server integration tests, not by this fixture.
(async () => {
  const fixture = await startFixture(); let browser;
  try {
    browser = await launchBrowser(); const context = await browser.newContext();
    await selectFixture(context, fixture.origin, 'scheduled-workspace');
    const page = await context.newPage(); page.setDefaultTimeout(10000);
    const errors = [], records = new Map(), drafts = new Map(), calls = [];
    let nextId = 1, nextDraft = 1, jobs = 0, schedules = 0;
    page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', dialog => dialog.accept());
    const json = (route, value, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) });
    await page.route('**/api/v1/drafts/load', async route => {
      const body = route.request().postDataJSON(), id = `A${nextDraft++}`;
      calls.push({ action: 'validate-composition', body }); drafts.set(id, body);
      await json(route, { draftId: id, packageId: body.packageId, environment: body.environment, previewYaml: 'validated: true', redacted: false, expiresAt: new Date(Date.now() + 120000).toISOString() });
    });
    await page.route('**/api/v1/saved-load-scenarios**', async route => {
      const req = route.request(), url = new URL(req.url()), parts = url.pathname.split('/'), id = parts[parts.indexOf('saved-load-scenarios') + 1], action = parts.at(-1), method = req.method();
      const body = method === 'GET' ? null : req.postDataJSON(); calls.push({ action: `${method} ${action}`, body });
      if (method === 'GET' && !id) return json(route, { items: [...records.values()].map(({ definition, ...item }) => item), nextOffset: null });
      if (method === 'POST' && !id) {
        const authored = drafts.get(body.draftId); assert.ok(authored);
        const record = { id: `LS${nextId++}`, name: body.name, description: body.description, tags: body.tags, packageId: authored.packageId, model: 'virtualUsers', revision: 1, updatedAt: '', workloadCount: 1, definition: authored };
        records.set(record.id, record); return json(route, { id: record.id, revision: record.revision, name: record.name }, 201);
      }
      const record = records.get(id); if (!record) return json(route, { error: { summary: 'Not found' } }, 404);
      if (method === 'GET') return json(route, record);
      if (method === 'PATCH') {
        assert.equal(body.expectedRevision, record.revision); const authored = drafts.get(body.draftId); assert.ok(authored);
        Object.assign(record, { name: body.name, description: body.description, tags: body.tags, definition: authored, revision: record.revision + 1 }); return json(route, record);
      }
      if (method === 'POST' && action === 'validate') {
        if (body.expectedRevision !== record.revision) return json(route, { error: { code: 'ATT-SAVED-SCENARIO-CHANGED', summary: 'Saved Scenario changed; review and validate it again' } }, 409);
        const draftId = `A${nextDraft++}`; drafts.set(draftId, structuredClone(record));
        return json(route, { draftId, packageId: record.packageId, previewYaml: `revision: ${record.revision}`, redacted: false, expiresAt: new Date(Date.now() + 120000).toISOString() });
      }
      if (method === 'POST' && action === 'duplicate') {
        assert.equal(body.expectedRevision, record.revision); const copy = structuredClone(record); copy.id = `LS${nextId++}`; copy.name += ' (copy)'; copy.revision = 1; records.set(copy.id, copy); return json(route, copy, 201);
      }
      if (method === 'DELETE') { assert.equal(body.expectedRevision, record.revision); records.delete(id); return json(route, {}); }
      throw new Error(`Unexpected saved scenario request ${method} ${url.pathname}`);
    });
    await page.route('**/api/v1/jobs/load', async route => { jobs++; calls.push({ action: 'run', body: route.request().postDataJSON() }); return json(route, { jobId: 'JOB-4821', status: 'QUEUED', command: 'load' }, 202); });
    page.on('request', request => { const path = new URL(request.url()).pathname; if (request.method() === 'POST' && /\/(?:load-)?schedules$/.test(path)) { schedules++; calls.push({ action: 'schedule', body: request.postDataJSON() }); } });
    const base = fixture.origin + '/att/ui/';
    await page.goto(base + '#/advanced-load');
    await page.locator('.att-template-presets article').first().getByRole('button', { name: '＋ Create Load' }).click();
    await page.getByRole('heading', { name: 'Single VU', exact: true }).waitFor();
    await page.getByRole('button', { name: '⚙ Choose Target' }).click();
    const target = page.getByRole('dialog', { name: 'Configure Target' });
    await target.getByLabel('Target logical ID').fill('checkout'); await target.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('button', { name: '✓ Validate' }).click();
    await page.getByRole('button', { name: 'Save Scenario…' }).click();
    const save = page.getByRole('dialog', { name: 'Save Scenario' }); await save.getByLabel('Scenario name').fill('Traffic'); await save.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByText('Saved Traffic (revision 1).', { exact: false }).waitFor();
    assert.equal(jobs, 0); assert.equal(schedules, 0); assert.equal(records.size, 1);
    await page.getByRole('button', { name: 'Open Saved Scenarios' }).click();
    let saved = page.getByRole('region', { name: 'Saved scenarios' }); await saved.getByRole('row').filter({ hasText: 'Traffic' }).waitFor();
    await saved.getByRole('button', { name: 'Open / Edit' }).click();
    await page.getByRole('button', { name: '⚙ Configure' }).click();
    const config = page.getByRole('dialog', { name: 'Configure Workload' }); await config.locator('input[type=number]').first().fill('3'); await config.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('button', { name: '✓ Validate' }).click(); await page.getByRole('button', { name: 'Save Scenario…' }).click();
    await save.getByRole('button', { name: 'Save', exact: true }).click(); await page.getByText('Saved Traffic (revision 2).', { exact: false }).waitFor();
    assert.equal(records.get('LS1').revision, 2); assert.equal(records.get('LS1').definition.templateComposition.definition.workloads[0].load.users, 3); assert.equal(jobs, 0); assert.equal(schedules, 0);
    await page.getByRole('button', { name: 'Back to Saved Scenarios' }).click();
    saved = page.getByRole('region', { name: 'Saved scenarios' }); const row = saved.getByRole('row').filter({ hasText: 'Traffic' }); await row.waitFor();
    await row.getByRole('button', { name: 'Duplicate' }).click(); await saved.getByRole('row').filter({ hasText: 'Traffic (copy)' }).waitFor();
    await saved.getByRole('row').filter({ hasText: 'Traffic (copy)' }).getByRole('button', { name: 'Delete' }).click();
    await saved.getByRole('row').filter({ hasText: 'Traffic (copy)' }).waitFor({ state: 'hidden' });
    await row.getByRole('button', { name: 'Validate / Review' }).click();
    const review = page.getByRole('dialog', { name: 'Review Traffic' }); await review.waitFor(); assert.match(await review.innerText(), /Revision 2/);
    const scheduledDraft = [...drafts.keys()].at(-1);
    assert.equal(drafts.get(scheduledDraft).definition.templateComposition.definition.workloads[0].load.users, 3);
    await review.getByRole('button', { name: 'Schedule', exact: true }).click();
    await page.locator('#schedule-name').fill('Traffic schedule');
    await page.locator('#schedule-recurrence').selectOption('cron'); await page.locator('#schedule-cron').fill('0 2 * * *');
    const saveSchedule = page.getByRole('button', { name: 'Save schedule', exact: true }); await saveSchedule.waitFor();
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Save schedule' && !button.disabled));
    await saveSchedule.click(); await page.getByText('Traffic schedule', { exact: true }).first().waitFor();
    assert.equal(jobs, 0); assert.equal(schedules, 1); assert.equal(calls.find(call => call.action === 'schedule').body.draftId, scheduledDraft);
    await page.goto(base + '#/advanced-load?tab=scenarios');
    saved = page.getByRole('region', { name: 'Saved scenarios' }); const resumed = saved.getByRole('row').filter({ hasText: 'Traffic' }); await resumed.waitFor();
    records.get('LS1').revision = 3; // A concurrent edit occurs after this list was loaded.
    await resumed.getByRole('button', { name: 'Validate / Review' }).click();
    await saved.getByText(/Saved Scenario changed/).waitFor(); assert.equal(jobs, 0);
    await page.reload(); saved = page.getByRole('region', { name: 'Saved scenarios' });
    await saved.getByRole('row').filter({ hasText: 'Traffic' }).getByRole('button', { name: 'Validate / Review' }).click();
    await review.waitFor(); assert.match(await review.innerText(), /Revision 3/); await review.getByRole('button', { name: 'Run', exact: true }).click();
    await page.waitForURL('**/#/jobs/JOB-4821'); assert.equal(jobs, 1); assert.equal(schedules, 1);
    const runDraft = calls.find(call => call.action === 'run').body.draftId;
    assert.equal(runDraft, [...drafts.keys()].at(-1)); assert.equal(drafts.get(runDraft).definition.templateComposition.definition.workloads[0].load.users, 3);
    assert.deepEqual(errors, []);
    console.log('Saved scenario browser fixture passed: create, edit revision, duplicate, delete, validate, schedule and run.');
  } finally { if (browser) await browser.close(); await stopFixture(fixture); }
})().catch(error => { console.error(error); process.exitCode = 1; });
