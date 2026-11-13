const assert = require('node:assert/strict');
const { chromium, launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');

async function assertAxe(page) {
  const audit = await page.evaluate(async () => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  }));
  assert.deepEqual(audit.violations.map(item => ({ id: item.id, nodes: item.nodes.map(node => node.target) })), []);
}

async function main() {
  const fixture = await startFixture();
  const browser = await launchBrowser(chromium);
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: fixture.origin });
    await context.addInitScript(() => localStorage.setItem('att-ui-theme', 'light'));
    let capabilityAvailable = true;
    const validationRequests = [];
    const unexpectedWrites = [];
    await context.route('**/api/v1/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      const path = url.pathname.slice(url.pathname.indexOf('/api/v1/') + '/api/v1/'.length);
      const respond = (status, body) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      if (request.method() === 'POST') {
        if (path === 'validation') {
          const validationRequest = request.postDataJSON();
          validationRequests.push(validationRequest);
          const complete = validationRequests.length > 1;
          return respond(200, {
            schemaVersion: 'att-validation/v1', scope: validationRequest.targetKind, backend: 'SERVER',
            sections: [{ name: `${validationRequest.targetKind} validation`, source: 'templates/flow.yaml', status: complete ? 'PASS' : 'WARN',
              diagnostics: complete ? [] : [{ code: 'ATT-VALIDATION-SAMPLE', severity: 'WARN',
                message: 'password=fixture-secret from /Users/private/config.yaml', source: 'config/config.yaml' }] }],
            diagnostics: complete ? [] : [{ code: 'ATT-VALIDATION-SAMPLE', severity: 'WARN',
              message: 'password=fixture-secret from /Users/private/config.yaml', source: 'config/config.yaml',
              resourceId: 'paymentFlow', suggestion: 'Refresh the source, then retry.' }],
            counts: { pass: complete ? 1 : 0, fail: 0, warn: complete ? 0 : 1, skip: 0, errors: 0, warnings: complete ? 0 : 1 },
            overallStatus: complete ? 'PASS' : 'PASS', complete, exitCode: complete ? 0 : 0,
            context: { packageId: 'payments', environment: 'SIT', revision: 'rev-355', serverPath: '/Users/private/config.yaml' },
            requestId: `validation-${validationRequests.length}`,
          });
        }
        unexpectedWrites.push(`${request.method()} ${path}`);
        return respond(500, { error: { summary: `Unexpected write: ${path}` } });
      }
      if (path === 'version') return respond(200, { version: '4.2.10', apiVersion: '1',
        validationApiVersion: capabilityAvailable ? 'att-validation/v1' : undefined,
        inlineLoadEnabled: true, loadSchedulingEnabled: false, authMode: 'container', authenticationRequired: false });
      if (path === 'auth/capabilities') return respond(200, { operate: true, accountManage: false,
        configurationAdmin: false, capabilities: { operate: true }, inspectionCacheContext: 'a'.repeat(43) });
      if (path === 'packages') return respond(200, { items: [{ packageId: 'payments', name: 'Payments' }] });
      if (path === 'jobs' || path.startsWith('jobs?')) return respond(200, { items: [], total: 0, offset: 0, limit: 100 });
      if (path === 'packages/payments/configuration' || path.startsWith('packages/payments/configuration?')) {
        return respond(200, { state: 'ready', environments: [{ name: 'SIT', state: 'active', default: true }, { name: 'UAT', state: 'active' }] });
      }
      if (path.startsWith('packages/payments/package-configuration/effective')) {
        return respond(200, { packageId: 'payments', present: true, canEdit: false, manifestDigest: 'a'.repeat(64), environment: 'SIT', environmentOrigin: 'package' });
      }
      if (path.startsWith('packages/payments/resources')) {
        return respond(200, { items: [
        ...(url.searchParams.get('type') === 'case'
          ? [{ type: 'case', resourceId: 'case-resource-17', logicalId: 'PAYMENT.create', name: 'Create payment' }]
          : [{ type: 'flow', resourceId: 'paymentFlow', logicalId: 'PAYMENT.submit', name: 'Payment submission', state: 'ready' }]),
        ], total: 1, nextCursor: null });
      }
      if (path === 'auth/me') return respond(401, { error: { summary: 'No local user' } });
      return respond(404, { error: { code: 'FIXTURE_NOT_FOUND', summary: `Unexpected API request: ${request.method()} ${path}` } });
    });

    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    const route = '#/validate?scope=flow&package=payments&environment=SIT&target=paymentFlow&resourceType=flow&resourceId=paymentFlow&resourceLogicalId=PAYMENT.submit';
    await page.goto(`${fixture.origin}/att/ui/${route}`);
    await page.getByRole('heading', { name: 'Validate', exact: true }).waitFor();
    await page.getByLabel('Validation target').waitFor();
    await page.getByRole('button', { name: 'Run Validation', exact: true }).waitFor({ state: 'visible' });
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]')?.disabled);
    assert.equal(validationRequests.length, 0, 'opening a scoped route must not validate automatically');
    assert.equal(unexpectedWrites.length, 0);

    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    await assertAxe(page);
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();
    await page.getByText('PARTIAL', { exact: true }).waitFor();
    assert.equal(validationRequests.length, 1);
    assert.deepEqual(validationRequests[0], { command: 'validate', targetKind: 'flow', packageId: 'payments', target: 'paymentFlow', environment: 'SIT' });
    assert.equal(unexpectedWrites.length, 0, 'read-only validation must not call drafts, jobs, or schedule mutation routes');
    const visibleReport = await page.locator('.att-validation-report').innerText();
    assert.ok(visibleReport.includes('[REDACTED]'));
    assert.ok(!visibleReport.includes('fixture-secret') && !visibleReport.includes('/Users/private'));
    await page.getByLabel('Filter diagnostics by code').fill('ATT-VALIDATION-SAMPLE');
    await page.getByLabel('Validation diagnostics').getByText('ATT-VALIDATION-SAMPLE').waitFor();
    await page.getByRole('button', { name: 'Copy safe report' }).click();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    assert.ok(copied.includes('[REDACTED]') && !copied.includes('fixture-secret') && !copied.includes('serverPath'));
    await page.getByRole('button', { name: 'Revalidate', exact: true }).click();
    await page.locator('.att-validation-status').getByText('PASS', { exact: true }).waitFor();
    assert.equal(validationRequests.length, 2);
    assert.equal(unexpectedWrites.length, 0);

    await page.goto(`${fixture.origin}/att/ui/#/validate?scope=case&package=payments&environment=SIT`);
    await page.getByRole('heading', { name: 'Validate', exact: true }).waitFor();
    await page.getByLabel('Validation target').selectOption('case-resource-17');
    await page.waitForFunction(() => !document.querySelector('button[type="submit"]')?.disabled);
    const caseBefore = validationRequests.length;
    await page.getByRole('button', { name: 'Run Validation', exact: true }).click();
    await page.getByRole('heading', { name: 'Validation report', exact: true }).waitFor();
    assert.deepEqual(validationRequests[caseBefore], { command: 'validate', targetKind: 'case', packageId: 'payments', target: 'case-resource-17', environment: 'SIT' },
      'Case validation must send its canonical Server resourceId rather than its display logicalId');
    assert.equal(unexpectedWrites.length, 0);

    await page.keyboard.press('Control+k');
    await page.locator('#command-search').fill('Validate');
    await page.getByRole('listitem').getByRole('button', { name: /Validate/ }).click();
    await page.waitForURL(url => url.hash.startsWith('#/validate?scope=config'));
    assert.equal(validationRequests.length, 3, 'keyboard navigation must not submit validation');

    await page.getByRole('button', { name: 'Switch to dark theme' }).click();
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= window.innerWidth);
    await assertAxe(page);
    await page.getByRole('heading', { name: 'Validate', exact: true }).waitFor();
    assert.deepEqual(pageErrors, []);

    capabilityAvailable = false;
    const oldServer = await context.newPage();
    await oldServer.goto(`${fixture.origin}/att/ui/${route}`);
    await oldServer.getByText(/requires att-validation\/v1/).waitFor();
    assert.equal(await oldServer.getByRole('button', { name: 'Run Validation' }).isDisabled(), true);
    assert.equal(validationRequests.length, 3, 'an unsupported Server version must not receive a validation request');
    await context.close();
    process.stdout.write('Read-only validation browser acceptance passed: explicit POST, no execution writes, schema capability gate, redacted report, keyboard navigation, axe, dark theme, and mobile layout under /att/.\n');
  } finally {
    await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
