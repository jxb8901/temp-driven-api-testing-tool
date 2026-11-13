const assert = require('node:assert/strict');
const { launchBrowser } = require('./browser-support.cjs');

async function main() {
  const origin = process.env.ATT_UI_SERVER_ORIGIN;
  const username = process.env.ATT_UI_SERVER_USERNAME;
  const password = process.env.ATT_UI_SERVER_PASSWORD;
  assert.ok(origin, 'ATT_UI_SERVER_ORIGIN is required');
  assert.ok(username && password, 'Tomcat test credentials are required');

  const browser = await launchBrowser();
  const context = await browser.newContext({ httpCredentials: { username, password, send: 'always' }, viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const validationRequests = [];
  const otherWrites = [];
  page.on('request', request => {
    if (!request.url().includes('/tools/att/api/v1/')) return;
    if (request.method() !== 'POST') return;
    const pathname = new URL(request.url()).pathname;
    if (pathname.endsWith('/validation')) validationRequests.push(request);
    else otherWrites.push(`${request.method()} ${pathname}`);
  });
  const getJobIds = async () => page.evaluate(async () => {
    const response = await fetch(new URL('../api/v1/jobs', document.baseURI), { cache: 'no-store' });
    if (!response.ok) throw new Error(`Jobs list returned HTTP ${response.status}`);
    const body = await response.json();
    return (body.items || []).map(item => item.jobId).sort();
  });

  try {
    const versionResponse = await page.request.get(`${origin}/tools/att/api/v1/version`);
    assert.equal(versionResponse.status(), 200);
    const version = await versionResponse.json();
    assert.equal(version.validationApiVersion, 'att-validation/v1', 'the packaged Server must advertise its validation capability');

    await page.goto(`${origin}/tools/att/ui/index.html#/validate?scope=config`);
    await page.getByRole('heading', { name: 'Validate', level: 1 }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Run Validation', exact: true }).isDisabled(), false);
    assert.equal(validationRequests.length, 0, 'opening the workspace must not submit validation');
    const beforeConfig = await getJobIds();
    const configResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/api/v1/validation'));
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    const configResponse = await configResponsePromise;
    assert.equal(configResponse.status(), 200);
    const configReport = await configResponse.json();
    assert.equal(configReport.schemaVersion, 'att-validation/v1');
    assert.equal(configReport.scope, 'CONFIG', 'the packaged Server serializes validation scope enums in uppercase');
    assert.ok(configReport.requestId);
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();
    assert.ok(['PASS', 'INVALID'].includes(await page.locator('.att-validation-status strong').textContent()),
      'the real packaged Server report must pass through the frontend validator and render its result');
    assert.deepEqual(await getJobIds(), beforeConfig, 'Config validation must not add a persisted Job');

    await page.goto(`${origin}/tools/att/ui/index.html#/configuration/payments`);
    await page.getByRole('heading', { name: 'Configuration', level: 1, exact: true }).waitFor();
    const sharedConfigResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/api/v1/validation'));
    await page.getByRole('button', { name: 'Read-only validate Shared Config', exact: true }).click();
    await page.getByRole('heading', { name: 'Validate', level: 1 }).waitFor();
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    const sharedConfigResponse = await sharedConfigResponsePromise;
    assert.equal(sharedConfigResponse.status(), 200);
    assert.deepEqual(await sharedConfigResponse.request().postDataJSON(), { command: 'validate', targetKind: 'config' },
      'the Shared Configuration shortcut must validate global Config without inheriting a selected Package');
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();

    await page.goto(`${origin}/tools/att/ui/index.html#/configuration/payments`);
    await page.getByRole('button', { name: 'Read-only validate payments effective Config', exact: true }).waitFor();
    const packageConfigResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/api/v1/validation'));
    await page.getByRole('button', { name: 'Read-only validate payments effective Config', exact: true }).click();
    await page.getByRole('heading', { name: 'Validate', level: 1 }).waitFor();
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]')?.disabled);
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    const packageConfigResponse = await packageConfigResponsePromise;
    assert.equal(packageConfigResponse.status(), 200);
    assert.deepEqual(await packageConfigResponse.request().postDataJSON(), {
      command: 'validate', targetKind: 'config', packageId: 'payments', environment: 'SIT',
    }, 'Package-effective Config validation must use the explicitly selected Package and Server default Environment');
    const packageConfigReport = await packageConfigResponse.json();
    assert.equal(packageConfigReport.schemaVersion, 'att-validation/v1');
    assert.equal(packageConfigReport.scope, 'CONFIG_PACKAGE');
    assert.equal(packageConfigReport.context.environment, 'SIT');
    try {
      await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor({ timeout: 5000 });
    } catch {
      const uiError = await page.locator('.att-validation-error').textContent();
      throw new Error(`Packaged Server report ${packageConfigReport.scope}/${packageConfigReport.overallStatus} was not rendered; UI error: ${uiError || 'none'}`);
    }

    await page.goto(`${origin}/tools/att/ui/index.html#/packages/payments`);
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    const detailActions = page.locator('.att-explorer-detail-actions');
    await detailActions.getByRole('button', { name: 'Read-only validate', exact: true }).click();
    await page.waitForURL(url => url.hash.startsWith('#/validate?scope=flow'));
    await page.getByRole('heading', { name: 'Validate', level: 1 }).waitFor();
    const target = new URLSearchParams(page.url().split('#')[1].split('?')[1]).get('target');
    assert.ok(target, 'Explorer context should carry the selected canonical resource ID');
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]')?.disabled);
    const beforeFlow = await getJobIds();
    const flowResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/api/v1/validation'));
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    const flowResponse = await flowResponsePromise;
    assert.equal(flowResponse.status(), 200);
    const flowReport = await flowResponse.json();
    assert.equal(flowReport.schemaVersion, 'att-validation/v1');
    assert.equal(flowReport.scope, 'CONFIG_PACKAGE');
    assert.deepEqual(await flowResponse.request().postDataJSON(), {
      command: 'validate', targetKind: 'flow', packageId: 'payments', target,
    });
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();
    assert.deepEqual(await getJobIds(), beforeFlow, 'resource validation must not add a persisted Job');
    assert.deepEqual(otherWrites, [], 'the read-only UI flow must not POST drafts, jobs, or schedules');
    assert.equal(validationRequests.length, 4);
    process.stdout.write('Packaged-WAR validation checks passed under /tools/att: Server capability, explicit global and Package-effective Config, Explorer Flow validation, att-validation/v1 reports, and no Job or draft writes.\n');
  } finally {
    await context.close();
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
