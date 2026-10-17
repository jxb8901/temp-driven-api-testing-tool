const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture();
  let browser;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, bypassCSP: true });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    const base = `${fixture.origin}/att/ui/`;

    await selectFixture(context, fixture.origin, 'loaded');
    const indexResponse = await page.goto(base);
    assert.equal(indexResponse.status(), 200, 'the UI must serve below the non-root /att context');
    await page.locator('#packages-state').getByText(/configured package/i).waitFor();
    await page.locator('#jobs-state').getByText(/recent job row/i).waitFor();
    await page.getByText('payments', { exact: true }).first().waitFor();
    await page.getByText('JOB-4821', { exact: true }).waitFor();
    assert.equal(new URL(page.url()).pathname, '/att/ui/', 'the UI should retain its non-root path');

    for (const [route, title, activeNav] of [
      ['#/packages', 'Packages', 'packages'],
      ['#/jobs', 'Jobs', 'jobs'],
      ['#/advanced-load', 'Load testing', 'load-testing'],
      ['#/settings', 'Settings', 'settings'],
    ]) {
      await page.goto(`${base}${route}`);
      if (route === '#/jobs' || route === '#/settings') await page.getByRole('heading', { name: title, exact: true }).waitFor();
      else if (route === '#/advanced-load') {
        await page.getByRole('heading', { name: 'LOAD Catalog', exact: true }).waitFor();
        await page.getByRole('heading', { name: 'Available Load YAML resources', exact: true }).waitFor();
      } else await page.locator('#home-title').getByText(title, { exact: true }).waitFor();
      assert.equal(await page.locator(`[data-nav="${activeNav}"]`).getAttribute('aria-current'), 'page');
      assert.equal(await page.locator('#breadcrumbs [aria-current="page"]').count(), 1);
    }

    await page.goto(base);
    await page.getByRole('button', { name: 'Open package' }).first().click();
    await page.locator('[data-nav="load-testing"]').click();
    await page.getByRole('heading', { name: 'LOAD Catalog', exact: true }).waitFor();
    await page.getByRole('heading', { name: 'Available Load YAML resources', exact: true }).waitFor();
    await page.getByRole('heading', { name: 'Recent LOAD Jobs', exact: true }).waitFor();
    await page.getByRole('button', { name: 'New LOAD', exact: true }).waitFor();
    await page.getByRole('button', { name: 'New LOAD', exact: true }).click();
    await page.getByRole('heading', { name: /New LOAD/i }).waitFor();
    await page.locator('[data-nav="load-testing"]').click();
    await page.getByRole('heading', { name: 'LOAD Catalog', exact: true }).waitFor();
    await page.getByRole('heading', { name: 'Available Load YAML resources', exact: true }).waitFor();

    await page.goto(`${base}#/settings`);
    await page.getByRole('radio', { name: /Dark/ }).check();
    assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'dark', 'Settings should apply the selected theme');
    await page.reload();
    await page.getByRole('heading', { name: 'Settings', exact: true }).waitFor();
    assert.equal(await page.getByRole('radio', { name: /Dark/ }).isChecked(), true, 'an explicit theme preference should persist in this browser');
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    const settingsAudit = await page.evaluate(async () => window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    }));
    assert.deepEqual(settingsAudit.violations.map(({ id, impact, nodes }) => ({
      id, impact, selectors: nodes.map(node => node.target),
    })), [], 'Settings should pass the configured WCAG accessibility rules');
    await page.getByRole('radio', { name: /System/ }).check();
    assert.equal(await page.evaluate(() => localStorage.getItem('att-ui-theme')), null, 'System should remove the explicit browser override');

    for (const [scenario, headers] of [
      ['authenticated', { Authorization: 'Basic YXR0LWZpeHR1cmU6cGFzcw==' }],
      ['anonymous', {}],
    ]) {
      await selectFixture(context, fixture.origin, scenario);
      await page.setExtraHTTPHeaders(headers);
      await page.goto(`${base}#/packages`);
      await page.locator('#packages-state').getByText(/2 configured packages/).waitFor();
      await page.goto(`${base}#/jobs`);
      await page.getByRole('heading', { name: 'Jobs', exact: true }).waitFor();
      await page.locator('.att-jobs-table tbody tr').first().waitFor();
    }

    await selectFixture(context, fixture.origin, 'authenticated');
    await page.setExtraHTTPHeaders({});
    await page.goto(base);
    await page.getByText(/Could not load packages/i).waitFor();
    await page.getByText(/Could not load recent jobs/i).waitFor();
    assert.equal(await page.locator('#retry-packages').isVisible(), true, 'authentication errors remain retryable');

    await selectFixture(context, fixture.origin, 'empty');
    await page.goto(base);
    await page.locator('#packages-state').getByText(/No packages are configured yet/i).waitFor();
    await page.locator('#jobs-state').getByText(/No jobs yet/i).waitFor();

    await selectFixture(context, fixture.origin, 'error');
    await page.goto(base);
    await page.getByText(/Could not load packages/i).waitFor();
    await page.getByText(/Could not load recent jobs/i).waitFor();
    assert.equal(await page.locator('#retry-packages').isVisible(), true);

    await selectFixture(context, fixture.origin, 'loading');
    await page.goto(base);
    await page.waitForFunction(() => document.querySelector('#packages-state')?.getAttribute('aria-busy') === 'true');
    await page.waitForFunction(() => document.querySelector('#jobs-state')?.getAttribute('aria-busy') === 'true');

    await selectFixture(context, fixture.origin, 'loaded');
    await page.goto(base);
    await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(() => document.activeElement.classList.contains('skip-link')), true, 'the first Tab should focus the skip link');
    assert.equal(await page.locator('.skip-link').evaluate(node => node.getBoundingClientRect().top >= 0), true, 'the focused skip link should be visible');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.activeElement?.id === 'main-content');

    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    const audit = await page.evaluate(async () => window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    }));
    assert.deepEqual(audit.violations.map(({ id, impact, help, nodes }) => ({
      id, impact, help, selectors: nodes.map(node => node.target),
    })), [], 'the dashboard should pass the configured WCAG accessibility rules');
    assert.deepEqual(pageErrors, [], 'the UI should not report uncaught browser errors');
    console.log('ATT UI browser smoke and axe-core accessibility checks passed.');
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
