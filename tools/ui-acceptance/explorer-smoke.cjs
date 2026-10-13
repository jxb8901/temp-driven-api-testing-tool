const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

function jsonResponse(route, status, body) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function main() {
  const fixture = await startFixture();
  let browser;
  let authFailure = false;
  let sourceCalls = 0;
  let submittedInput;
  let submittedPrincipal;
  let authorizationHeaderSeen = false;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: fixture.origin });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await selectFixture(context, fixture.origin, 'loaded');

    await page.route('**/api/v1/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      const path = url.pathname.slice(url.pathname.indexOf('/api/v1/') + '/api/v1/'.length);
      const method = request.method();
      if (request.headers().authorization) authorizationHeaderSeen = true;

      if (authFailure && path.endsWith('/source')) {
        return jsonResponse(route, 401, { error: { code: 'UNAUTHORIZED', summary: 'authorization header rejected: canary-401-secret' } });
      }
      if (method === 'GET' && path === 'version') {
        return jsonResponse(route, 200, { apiVersion: '1', version: '4.1.3', inlineLoadEnabled: true });
      }
      if (method === 'GET' && path === 'packages') {
        return jsonResponse(route, 200, { items: [{ packageId: 'payments', name: 'payments', state: 'ready' }], total: 1 });
      }
      if (method === 'GET' && path === 'jobs') return jsonResponse(route, 200, { items: [], total: 0 });
      if (method === 'GET' && path === 'jobs/job-debug-99') {
        return jsonResponse(route, 200, { jobId: 'job-debug-99', packageId: 'payments', status: 'QUEUED', command: 'DEBUG' });
      }
      if (method === 'GET' && path === 'packages/payments/resources') {
        return jsonResponse(route, 200, {
          items: [
            { type: 'template', resourceId: 'checkout', logicalId: 'checkout', name: 'checkout', state: 'ready', sourceAvailable: true },
            { type: 'template', resourceId: 'empty', logicalId: 'empty', name: 'empty', state: 'ready', sourceAvailable: true },
          ], total: 2, nextCursor: null,
        });
      }
      const detailMatch = path.match(/^packages\/payments\/resources\/template\/(checkout|empty)$/);
      if (method === 'GET' && detailMatch) {
        const id = detailMatch[1];
        return jsonResponse(route, 200, {
          resource: { type: 'template', resourceId: id, logicalId: id, name: id, state: 'ready', sourceAvailable: true, references: [], referencedBy: [] },
          definition: { template: id, inputs: ['credential'] },
        });
      }
      const sourceMatch = path.match(/^packages\/payments\/resources\/template\/(checkout|empty)\/source$/);
      if (method === 'GET' && sourceMatch) {
        sourceCalls += 1;
        const id = sourceMatch[1];
        return jsonResponse(route, 200, {
          available: true,
          format: 'yaml',
          text: id === 'empty' ? '' : Array.from({ length: 5000 }, (_, index) => `step-${index + 1}: ${index === 0 ? '<img src=x onerror=alert(1)>' : 'safe'}`).join('\n'),
        });
      }
      if (method === 'GET' && path === 'packages/payments/configuration') {
        return jsonResponse(route, 200, {
          state: 'ready', globals: {}, sections: [],
          environments: [{ name: 'staging', state: 'active', default: true }, { name: 'production', state: 'active' }],
        });
      }
      if (method === 'GET' && path === 'packages/payments/resources/template/checkout/debug-form') {
        return jsonResponse(route, 200, {
          target: { type: 'template', id: 'checkout' },
          input: { inputs: { credential: { $attDebugKeepDefault: '/inputs/credential' } }, vars: { retries: 2 } },
          redacted: true,
        });
      }
      if (method === 'POST' && path === 'drafts/debug') {
        const body = request.postDataJSON();
        submittedInput = body.input;
        return jsonResponse(route, 200, { draftId: 'draft-99', previewYaml: 'target: checkout\nstatus: valid', expiresAt: new Date(Date.now() + 60_000).toISOString(), redacted: true });
      }
      if (method === 'POST' && path === 'jobs/debug') {
        const body = request.postDataJSON();
        submittedPrincipal = body.principal;
        return jsonResponse(route, 202, { jobId: 'job-debug-99' });
      }
      return jsonResponse(route, 404, { error: { code: 'FIXTURE_NOT_FOUND', summary: `No Explorer fixture route: ${method} ${path}` } });
    });

    const base = `${fixture.origin}/att/ui/`;
    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('tab', { name: 'Definition' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByRole('tab', { name: 'Source' }).waitFor({ state: 'visible' });
    await page.waitForFunction(() => document.querySelector('[role="tab"][data-tab="source"]')?.getAttribute('aria-selected') === 'true');
    await page.getByText(/5[,.]?000 lines/).waitFor();
    assert.equal(await page.locator('.att-source-line').count() <= 60, true, 'thousands of lines should remain virtualized');
    await page.locator('.att-source-viewport').evaluate(viewport => {
      viewport.scrollTop = viewport.scrollHeight;
      viewport.dispatchEvent(new Event('scroll', { bubbles: true }));
    });
    await page.getByText('step-5000: safe', { exact: false }).waitFor();
    assert.equal(await page.locator('.att-source-viewport img').count(), 0, 'source is rendered as text, not HTML');
    await page.getByRole('button', { name: 'Copy displayed source' }).click();
    await page.getByText('Copied safe source.').waitFor();
    assert.equal((await page.evaluate(() => navigator.clipboard.readText())).includes('step-5000: safe'), true);
    await page.getByRole('button', { name: 'Refresh source' }).click();
    await page.getByRole('button', { name: 'Refresh source' }).waitFor();
    await page.getByRole('button', { name: 'Refresh source' }).click();
    await page.getByRole('button', { name: 'Refresh source' }).waitFor();
    assert.equal(sourceCalls >= 3, true, 'source can be refreshed repeatedly');

    await page.goto(`${base}#/packages/payments/resources/template/empty`);
    await page.getByRole('heading', { name: 'empty' }).waitFor();
    await page.getByRole('tab', { name: 'Definition' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByText('Safe source is empty.').waitFor();
    assert.equal(await page.getByRole('button', { name: 'Copy displayed source' }).isDisabled(), true);

    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('button', { name: 'Debug this target' }).click();
    await page.getByText(/Hidden package-authored values are preserved/).waitFor();
    const keepDefault = page.getByRole('checkbox', { name: /Keep hidden package default/ });
    await keepDefault.waitFor();
    assert.equal(await keepDefault.isChecked(), true, 'redacted defaults remain untouched until the user opts out');
    assert.equal(await page.locator('body').innerText().then(text => text.includes('real-secret')), false, 'secret defaults are not exposed');
    await keepDefault.uncheck();
    await page.getByRole('combobox', { name: 'credential value type' }).selectOption('string');
    await page.getByRole('textbox', { name: 'credential value' }).fill('explicit-override');

    await page.locator('[data-nav="jobs"]').click();
    const dialog = page.getByRole('dialog', { name: 'Discard your changes?' });
    await dialog.waitFor({ state: 'visible' });
    await dialog.getByRole('button', { name: 'Stay on this page' }).click();
    await dialog.waitFor({ state: 'hidden' });
    assert.match(page.url(), /resources\/template\/checkout$/);

    await page.getByRole('button', { name: 'Reset defaults' }).click();
    await keepDefault.waitFor();
    assert.equal(await keepDefault.isChecked(), true, 'reset restores the redacted marker');
    await keepDefault.uncheck();
    await page.getByRole('combobox', { name: 'credential value type' }).selectOption('string');
    await page.getByRole('textbox', { name: 'credential value' }).fill('submitted-override');
    await page.getByLabel('Anonymous job label (optional)').fill('anonymous-e2e');
    page.once('dialog', dialogEvent => dialogEvent.accept());
    await page.getByRole('button', { name: 'Start Debug' }).click();
    await page.getByRole('heading', { name: 'Job job-debug-99' }).waitFor();
    assert.deepEqual(submittedInput, { inputs: { credential: 'submitted-override' }, vars: { retries: 2 } });
    assert.equal(submittedPrincipal, 'anonymous-e2e');
    assert.equal(authorizationHeaderSeen, false, 'the anonymous flow does not add an authorization header');
    assert.equal(JSON.stringify(submittedInput).includes('real-secret'), false);

    await page.goBack();
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('button', { name: 'Debug this target' }).click();
    await page.getByText(/Hidden package-authored values are preserved/).waitFor();
    await page.getByRole('checkbox', { name: /Keep hidden package default/ }).uncheck();
    await page.getByRole('combobox', { name: 'credential value type' }).selectOption('string');
    await page.getByRole('textbox', { name: 'credential value' }).fill('discard-me');
    await page.locator('[data-nav="jobs"]').click();
    const discardDialog = page.getByRole('dialog', { name: 'Discard your changes?' });
    await discardDialog.waitFor({ state: 'visible' });
    await discardDialog.getByRole('button', { name: 'Discard changes and continue' }).click();
    await page.getByRole('heading', { name: 'Jobs and results' }).waitFor();
    assert.match(page.url(), /#\/jobs$/);

    authFailure = true;
    await page.goto(`${base}#/packages/payments/resources/template/checkout`);
    await page.getByRole('heading', { name: 'checkout' }).waitFor();
    await page.getByRole('tab', { name: 'Definition' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.getByText('Your session has expired. Sign in again, then refresh safe source.').waitFor();
    assert.equal(await page.locator('body').innerText().then(text => text.includes('canary-401-secret')), false, 'auth errors must not expose response bodies');
    assert.deepEqual(pageErrors, [], 'Explorer interactions should not produce uncaught browser errors');
    console.log('ATT Explorer Playwright smoke passed: virtualized and empty source, clipboard/refresh, redaction, dirty navigation, anonymous job, and 401 handling.');
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
