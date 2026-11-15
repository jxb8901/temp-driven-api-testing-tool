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
  const validationRecordRequests = [];
  const otherWrites = [];
  page.on('request', request => {
    if (!request.url().includes('/tools/att/api/v1/')) return;
    if (request.method() !== 'POST') return;
    const pathname = new URL(request.url()).pathname;
    if (pathname.endsWith('/api/v1/validation')) validationRequests.push(request);
    else if (pathname.endsWith('/api/v1/validations')) validationRecordRequests.push(request);
    else otherWrites.push(`${request.method()} ${pathname}`);
  });
  const getJobIds = async () => page.evaluate(async () => {
    const response = await fetch(new URL('../api/v1/jobs', document.baseURI), { cache: 'no-store' });
    if (!response.ok) throw new Error(`Jobs list returned HTTP ${response.status}`);
    const body = await response.json();
    return (body.items || []).map(item => item.jobId).sort();
  });
  const waitForRecordReport = async validationId => {
    const url = `${origin}/tools/att/api/v1/validations/${encodeURIComponent(validationId)}`;
    const deadline = Date.now() + 60000;
    let record;
    while (Date.now() < deadline) {
      const response = await page.request.get(url, { headers: { 'Cache-Control': 'no-cache' } });
      assert.equal(response.status(), 200, `Validation record ${validationId} should remain readable while polling`);
      record = await response.json();
      if (record.report) break;
      assert.ok(['QUEUED', 'RUNNING'].includes(record.status),
        `Validation record ${validationId} ended as ${record.status} without a report: ${JSON.stringify(record)}`);
      await page.waitForTimeout(250);
    }
    assert.ok(record?.report, `Validation record ${validationId} did not produce a report within 60 seconds: ${JSON.stringify(record)}`);
    await page.getByText('View full report and diagnostics', { exact: true }).waitFor();
    return record;
  };
  const nextRecordSubmission = () => page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/api/v1/validations'));
  const assertContextValidationVisible = async () => {
    const panel = page.locator('.att-validation-context-panel');
    await page.waitForFunction(() => {
      const panelElement = document.querySelector('.att-validation-context-panel');
      const status = panelElement?.querySelector('.att-validation-status');
      const heading = panelElement?.querySelector('h2');
      if (!status || !heading) return false;
      const rect = status.getBoundingClientRect();
      return document.activeElement === heading && rect.top >= 0 && rect.bottom <= window.innerHeight;
    }, undefined, { timeout: 10000 });
    assert.equal(await panel.locator('[role="status"]').count(), 1,
      'the newly opened contextual validation status must be visible and announced');
  };

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
    const beforeSharedConfig = await getJobIds();
    const sharedConfigResponsePromise = nextRecordSubmission();
    await page.getByRole('button', { name: 'Validate Shared Config', exact: true }).click();
    const sharedConfigResponse = await sharedConfigResponsePromise;
    assert.equal(sharedConfigResponse.status(), 202);
    await assertContextValidationVisible();
    assert.deepEqual(await sharedConfigResponse.request().postDataJSON(), { command: 'validate', targetKind: 'config' },
      'the Shared Configuration shortcut must validate global Config without inheriting a selected Package');
    const sharedConfigInitial = await sharedConfigResponse.json();
    const sharedConfigRecord = await waitForRecordReport(sharedConfigInitial.validationId);
    assert.equal(sharedConfigRecord.report.schemaVersion, 'att-validation/v1');
    assert.equal(sharedConfigRecord.report.scope, 'CONFIG');
    assert.deepEqual(await getJobIds(), beforeSharedConfig, 'Shared Config validation must not add a persisted Job');

    await page.goto(`${origin}/tools/att/ui/index.html#/configuration/payments`);
    await page.getByRole('button', { name: 'Validate Effective Config', exact: true }).waitFor();
    const beforePackageConfig = await getJobIds();
    const packageConfigResponsePromise = nextRecordSubmission();
    await page.getByRole('button', { name: 'Validate Effective Config', exact: true }).click();
    const packageConfigResponse = await packageConfigResponsePromise;
    assert.equal(packageConfigResponse.status(), 202);
    assert.deepEqual(await packageConfigResponse.request().postDataJSON(), {
      command: 'validate', targetKind: 'config', packageId: 'payments',
    }, 'Package-effective Config validation must keep the selected Package in its submission context');
    const packageConfigInitial = await packageConfigResponse.json();
    const packageConfigRecord = await waitForRecordReport(packageConfigInitial.validationId);
    assert.equal(packageConfigRecord.environment, 'SIT', 'the Server must capture the Package default Environment into the durable record');
    assert.equal(packageConfigRecord.report.schemaVersion, 'att-validation/v1');
    assert.equal(packageConfigRecord.report.scope, 'CONFIG_PACKAGE');
    assert.equal(packageConfigRecord.report.context.environment, 'SIT');
    assert.deepEqual(await getJobIds(), beforePackageConfig, 'Package-effective Config validation must not add a persisted Job');

    await page.goto(`${origin}/tools/att/ui/index.html#/packages/payments`);
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    const detailActions = page.locator('.att-explorer-detail-actions');
    await detailActions.getByRole('button', { name: 'Validate', exact: true }).waitFor();
    const resourceRoute = page.url().split('#')[1] || '';
    const routeMatch = resourceRoute.match(/\/resources\/[^/]+\/([^/?]+)/);
    const target = routeMatch ? decodeURIComponent(routeMatch[1]) : '';
    assert.ok(target, 'Explorer context should carry the selected canonical resource ID');
    const beforeFlow = await getJobIds();
    const flowResponsePromise = nextRecordSubmission();
    await detailActions.getByRole('button', { name: 'Validate', exact: true }).click();
    const flowResponse = await flowResponsePromise;
    assert.equal(flowResponse.status(), 202);
    await assertContextValidationVisible();
    assert.deepEqual(await flowResponse.request().postDataJSON(), {
      command: 'validate', targetKind: 'flow', packageId: 'payments', target,
    });
    const flowInitial = await flowResponse.json();
    const flowRecord = await waitForRecordReport(flowInitial.validationId);
    assert.equal(flowRecord.report.schemaVersion, 'att-validation/v1');
    assert.equal(flowRecord.report.scope, 'CONFIG_PACKAGE');
    assert.deepEqual(await getJobIds(), beforeFlow, 'resource validation must not add a persisted Job');

    const invalidCatalogResponse = await page.request.get(`${origin}/tools/att/api/v1/packages/payments/resources?type=template&query=STUDIO_INVALID&limit=100`);
    assert.equal(invalidCatalogResponse.status(), 200);
    const invalidCatalog = await invalidCatalogResponse.json();
    const invalidTemplate = (invalidCatalog.items || []).find(item => item.logicalId === 'STUDIO_INVALID');
    assert.ok(invalidTemplate, 'the real authorized catalog must expose the invalid Template fixture');
    assert.equal(invalidTemplate.state, 'invalid');

    const selectedTarget = new URLSearchParams({ scope: 'template', package: 'payments', environment: 'SIT',
      target: invalidTemplate.resourceId, resourceType: 'template', resourceId: invalidTemplate.resourceId,
      resourceLogicalId: invalidTemplate.logicalId });
    await page.goto(`${origin}/tools/att/ui/index.html#/validate?${selectedTarget.toString()}`);
    await page.getByRole('heading', { name: 'Validate', level: 1 }).waitFor();
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]')?.disabled);
    const beforeInvalidTemplate = await getJobIds();
    const invalidTemplateResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/api/v1/validation'));
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    const invalidTemplateResponse = await invalidTemplateResponsePromise;
    assert.equal(invalidTemplateResponse.status(), 200);
    assert.deepEqual(await invalidTemplateResponse.request().postDataJSON(), {
      command: 'validate', targetKind: 'template', packageId: 'payments', target: invalidTemplate.resourceId, environment: 'SIT',
    });
    const invalidTemplateReport = await invalidTemplateResponse.json();
    assert.equal(invalidTemplateReport.overallStatus, 'INVALID');
    const selectedDiagnostic = (invalidTemplateReport.diagnostics || []).find(item => item.code === 'ATT-RESOURCE-TEMPLATE-INVALID');
    assert.ok(selectedDiagnostic, JSON.stringify(invalidTemplateReport));
    assert.equal(selectedDiagnostic.severity, 'ERROR');
    assert.equal(typeof selectedDiagnostic.message, 'string');
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();
    assert.equal(await page.locator('.att-validation-error').count(), 0, 'an INVALID resource report must render instead of failing report parsing');
    assert.ok((await page.locator('.att-validation-diagnostic-list').textContent()).includes('Template definition is invalid'));
    const selectedResourceLink = page.getByRole('button', { name: 'Open authorized resource', exact: true }).first();
    await selectedResourceLink.waitFor();
    await selectedResourceLink.click();
    await page.getByRole('heading', { name: 'Resource details', exact: true }).waitFor();
    assert.deepEqual(await getJobIds(), beforeInvalidTemplate, 'selected-resource validation must not add a persisted Job');

    const brokenClosureCatalogResponse = await page.request.get(`${origin}/tools/att/api/v1/packages/payments/resources?type=template&query=STUDIO_BROKEN_CLOSURE&limit=100`);
    assert.equal(brokenClosureCatalogResponse.status(), 200);
    const brokenClosureCatalog = await brokenClosureCatalogResponse.json();
    const brokenClosure = (brokenClosureCatalog.items || []).find(item => item.logicalId === 'STUDIO_BROKEN_CLOSURE');
    assert.ok(brokenClosure, 'the authorized catalog should expose the valid Template with a missing Flow');
    assert.equal(brokenClosure.state, 'ready', 'metadata inspection must remain separate from static validation');
    assert.equal(brokenClosure.executionReadiness.state, 'dependency-unresolved');
    const brokenClosureRoute = new URLSearchParams({ scope: 'template', package: 'payments', environment: 'SIT',
      target: brokenClosure.resourceId, resourceType: 'template', resourceId: brokenClosure.resourceId,
      resourceLogicalId: brokenClosure.logicalId });
    await page.goto(`${origin}/tools/att/ui/index.html#/validate?${brokenClosureRoute.toString()}`);
    await page.getByRole('heading', { name: 'Validate', level: 1 }).waitFor();
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]')?.disabled);
    const beforeBrokenClosure = await getJobIds();
    const brokenClosureResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/api/v1/validation'));
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    const brokenClosureResponse = await brokenClosureResponsePromise;
    assert.equal(brokenClosureResponse.status(), 200);
    const brokenClosureReport = await brokenClosureResponse.json();
    assert.equal(brokenClosureReport.overallStatus, 'INVALID', JSON.stringify(brokenClosureReport));
    assert.equal(brokenClosureReport.exitCode, 2, JSON.stringify(brokenClosureReport));
    assert.ok((brokenClosureReport.diagnostics || []).some(item => item.code === 'ATT-RESOURCE-REFERENCE-UNRESOLVED'
      && item.severity === 'ERROR'), JSON.stringify(brokenClosureReport));
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();
    assert.equal(await page.locator('.att-validation-error').count(), 0, 'a missing dependency must render as INVALID, not a broken report');
    assert.equal((await page.locator('.att-validation-status strong').textContent())?.trim(), 'INVALID');
    assert.deepEqual(await getJobIds(), beforeBrokenClosure, 'dependency-closure validation must not add a persisted Job');

    const packageRoute = new URLSearchParams({ scope: 'package', package: 'payments', environment: 'SIT' });
    await page.goto(`${origin}/tools/att/ui/index.html#/validate?${packageRoute.toString()}`);
    await page.getByRole('heading', { name: 'Validate', level: 1 }).waitFor();
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]')?.disabled);
    const beforePackageValidation = await getJobIds();
    const packageValidationResponsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/api/v1/validation'));
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    const packageValidationResponse = await packageValidationResponsePromise;
    assert.equal(packageValidationResponse.status(), 200);
    assert.deepEqual(await packageValidationResponse.request().postDataJSON(), {
      command: 'validate', targetKind: 'package', packageId: 'payments', environment: 'SIT',
    });
    const packageReport = await packageValidationResponse.json();
    assert.equal(packageReport.overallStatus, 'INVALID');
    const packageDiagnostic = (packageReport.diagnostics || []).find(item => item.file === 'templates/STUDIO_INVALID/template.yaml');
    assert.ok(packageDiagnostic, JSON.stringify(packageReport));
    assert.equal(packageDiagnostic.template, 'STUDIO_INVALID');
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();
    const packageResourceLink = page.getByRole('button', { name: 'Open authorized resource', exact: true }).first();
    await packageResourceLink.waitFor();
    await packageResourceLink.click();
    await page.getByRole('heading', { name: 'Resource details', exact: true }).waitFor();
    assert.deepEqual(await getJobIds(), beforePackageValidation, 'Package validation must not add a persisted Job');

    assert.deepEqual(otherWrites, [], 'the read-only UI flow must not POST drafts, jobs, or schedules');
    assert.equal(validationRequests.length, 4, 'the manual validation workspace keeps its synchronous report contract');
    assert.equal(validationRecordRequests.length, 3, 'Shared Config, effective Config, and Explorer context use durable validation records');
    process.stdout.write('Packaged-WAR validation checks passed under /tools/att: real Config and Flow reports, invalid resource and dependency-closure diagnostics, Package-wide authorized source navigation, and no Job or draft writes.\n');
  } finally {
    await context.close();
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
