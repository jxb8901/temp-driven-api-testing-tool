const assert = require('node:assert/strict');
const { launchBrowser } = require('./browser-support.cjs');

(async () => {
  const origin = process.env.ATT_UI_SERVER_ORIGIN;
  assert.ok(origin && process.env.ATT_UI_SERVER_USERNAME && process.env.ATT_UI_SERVER_PASSWORD, 'A running authenticated ATT Server is required');
  const browser = await launchBrowser();
  const context = await browser.newContext({ httpCredentials: { username: process.env.ATT_UI_SERVER_USERNAME, password: process.env.ATT_UI_SERVER_PASSWORD, send: 'always' } });
  const page = await context.newPage(); page.setDefaultTimeout(30000); page.on('dialog', dialog => dialog.accept());
  const pageErrors = [], submissions = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('request', request => { const path = new URL(request.url()).pathname; if (request.method() === 'POST' && (/\/jobs\/load$/.test(path) || /\/(?:load-)?schedules$/.test(path))) submissions.push(path); });
  const base = `${origin}/tools/att/ui/index.html`, api = `${origin}/tools/att/api/v1`;
  async function get(path) { const response = await context.request.get(api + path); assert.equal(response.status(), 200, `${path}: ${await response.text()}`); return response.json(); }
  async function waitForJob(id) {
    const end = Date.now() + 60000; let job;
    do { job = await get(`/jobs/${id}`); if (['PASS','FAIL','ERROR','INVALID','CANCELLED'].includes(job.status)) break; await new Promise(resolve => setTimeout(resolve, 250)); } while (Date.now() < end);
    assert.equal(job?.status, 'PASS', `Job ${id} did not pass: ${JSON.stringify(job)}`); return job;
  }
  try {
    await page.goto(`${base}#/advanced-load`);
    await page.getByRole('heading', { name: 'Load testing', exact: true }).waitFor();
    await page.locator('.att-template-presets article').first().getByRole('button', { name: '＋ Create Load' }).click();
    await page.getByRole('heading', { name: 'Single VU', exact: true }).waitFor();
    await page.getByRole('button', { name: '⚙ Choose Target' }).click();
    const target = page.getByRole('dialog', { name: 'Configure Target' }); await target.getByLabel('Target logical ID').fill('SCHEDULED');
    await target.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('button', { name: '⚙ Common Settings' }).click();
    const settings = page.getByRole('dialog', { name: 'Common Settings' }); await settings.getByLabel('Measured duration value').fill('1'); await settings.getByLabel('Measured duration unit').selectOption('s'); await settings.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('button', { name: '⚙ Configure' }).click();
    const workload = page.getByRole('dialog', { name: 'Configure Workload' }); await workload.locator('input[type=number]').first().fill('2'); await workload.getByRole('button', { name: 'Save', exact: true }).click();
    const validationResponse = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/drafts/load'));
    await page.getByRole('button', { name: '✓ Validate' }).click();
    const validation = await validationResponse; assert.ok(validation.ok(), `Scenario validation failed: ${validation.status()} ${await validation.text()}`);
    await page.getByRole('button', { name: 'Save Scenario…' }).click();
    const save = page.getByRole('dialog', { name: 'Save Scenario' }); await save.getByLabel('Scenario name').fill('Browser H2 scenario'); await save.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByText('Saved Browser H2 scenario (revision 1).', { exact: false }).waitFor(); assert.deepEqual(submissions, []);
    await page.getByRole('button', { name: 'Open Saved Scenarios' }).click();
    const saved = page.getByRole('region', { name: 'Saved scenarios' }); let row = saved.getByRole('row').filter({ hasText: 'Browser H2 scenario' }); await row.waitFor();
    const list = await get('/saved-load-scenarios'); const record = list.items.find(item => item.name === 'Browser H2 scenario'); assert.ok(record);
    await page.reload(); row = saved.getByRole('row').filter({ hasText: 'Browser H2 scenario' }); await row.waitFor();
    await row.getByRole('button', { name: 'Validate / Review' }).click(); const review = page.getByRole('dialog', { name: 'Review Browser H2 scenario' }); await review.waitFor();
    assert.match(await review.innerText(), /Revision 1/); await review.getByRole('button', { name: 'Schedule', exact: true }).click();
    await page.locator('#schedule-name').fill('Browser snapshot schedule'); await page.locator('#schedule-recurrence').selectOption('cron'); await page.locator('#schedule-cron').fill('0 2 * * *');
    const scheduleResponse = page.waitForResponse(response => response.request().method() === 'POST' && /\/(?:load-)?schedules$/.test(new URL(response.url()).pathname));
    const scheduleButton = page.getByRole('button', { name: 'Save schedule', exact: true }); await scheduleButton.waitFor();
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Save schedule' && !button.disabled)); await scheduleButton.click();
    const scheduleResult = await scheduleResponse; assert.equal(scheduleResult.status(), 201, await scheduleResult.text()); const schedule = await scheduleResult.json();
    assert.equal(schedule.savedScenarioId, record.id); assert.equal(schedule.savedScenarioRevision, 1); assert.equal(submissions.filter(path => path.endsWith('/jobs/load')).length, 0);
    await page.goto(`${base}#/advanced-load?tab=scenarios`); row = saved.getByRole('row').filter({ hasText: 'Browser H2 scenario' }); await row.getByRole('button', { name: 'Open / Edit' }).click();
    await page.getByRole('button', { name: '⚙ Configure' }).click(); await workload.locator('input[type=number]').first().fill('3'); await workload.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('button', { name: '✓ Validate' }).click(); await page.getByRole('button', { name: 'Save Scenario…' }).click(); await save.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByText('Saved Browser H2 scenario (revision 2).', { exact: false }).waitFor();
    const edited = await get(`/saved-load-scenarios/${record.id}`); assert.equal(edited.revision, 2); assert.equal(edited.definition.templateComposition.definition.workloads[0].load.users, 3);
    await page.getByRole('button', { name: 'Back to Saved Scenarios' }).click(); row = saved.getByRole('row').filter({ hasText: 'Browser H2 scenario' });
    await row.getByRole('button', { name: 'Validate / Review' }).click(); await review.waitFor(); assert.match(await review.innerText(), /Revision 2/);
    const runResponse = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/jobs/load'));
    await review.getByRole('button', { name: 'Run', exact: true }).click(); const accepted = await runResponse; assert.equal(accepted.status(), 202, await accepted.text()); const run = await accepted.json(); await waitForJob(run.jobId);
    const runReplay = await get(`/jobs/${run.jobId}/replay`); assert.equal(runReplay.definition.scenario.workloads[0].load.users, 3);
    await page.goto(`${base}#/advanced-load?tab=scenarios`); row = saved.getByRole('row').filter({ hasText: 'Browser H2 scenario' }); await row.getByRole('button', { name: 'Delete' }).click(); await row.waitFor({ state: 'hidden' });
    await page.goto(`${base}#/schedules`); await page.getByRole('button', { name: 'Run Now Browser snapshot schedule' }).first().click();
    const triggerResponse = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith(`/${schedule.id}/trigger`));
    await page.getByRole('button', { name: 'Confirm Run Now' }).click(); const trigger = await triggerResponse; assert.equal(trigger.status(), 202, await trigger.text()); const occurrence = await trigger.json(); await waitForJob(occurrence.jobId);
    const scheduledReplay = await get(`/jobs/${occurrence.jobId}/replay`); assert.equal(scheduledReplay.definition.scenario.workloads[0].load.users, 2);
    assert.deepEqual(pageErrors, []); console.log('Real Server saved-scenario lifecycle passed: H2 revision, validated Run and independent scheduled snapshot.');
  } finally { await context.close(); await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
