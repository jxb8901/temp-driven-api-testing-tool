const assert = require('node:assert/strict');
const { launchBrowser } = require('./browser-support.cjs');

async function main() {
  const origin = process.env.ATT_UI_SERVER_ORIGIN;
  const username = process.env.ATT_UI_SERVER_USERNAME;
  const password = process.env.ATT_UI_SERVER_PASSWORD;
  assert.ok(origin, 'ATT_UI_SERVER_ORIGIN is required');
  assert.ok(username && password, 'Tomcat test credentials are required');

  const browser = await launchBrowser();
  const context = await browser.newContext({
    httpCredentials: { username, password, send: 'always' },
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  const navigationEvents = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigationEvents.push(frame.url()); });

  try {
    await page.goto(`${origin}/tools/att/ui/index.html#/packages/payments`);
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    await page.getByRole('heading', { name: 'Reference Flow' }).waitFor();

    await page.getByRole('tab', { name: 'Source' }).click();
    await page.getByRole('tabpanel', { name: 'Source' }).getByText('Code viewer ready.').waitFor();
    const fileLink = page.locator('.att-explorer-source [data-att-reference]');
    await fileLink.waitFor();
    assert.equal(await fileLink.getAttribute('data-att-file'), 'templates/SCHEDULED/payload/request.xml');
    const referenceLine = await fileLink.evaluate(element => {
      const lines = Array.from(element.closest('.cm-content')?.querySelectorAll('.cm-line') || []);
      return lines.findIndex(line => line.contains(element)) + 1;
    });
    assert.ok(referenceLine > 0, 'the projected source reference should have a CodeMirror line');
    const goToLine = page.locator('.att-explorer-source .att-code-goto input');
    await goToLine.fill(String(referenceLine));
    await page.locator('.att-explorer-source .att-code-editor').getByRole('button', { name: 'Go' }).click();
    await fileLink.click();
    try {
      await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor({ timeout: 10000 });
    } catch (error) {
      const headings = await page.locator('h1').allTextContents();
      const body = (await page.locator('body').innerText()).slice(0, 1200);
      const dialogs = await page.locator('dialog').evaluateAll(elements => elements.map(element => ({ open: element.open, text: element.innerText })));
      const link = await fileLink.evaluate(element => element.outerHTML);
      throw new Error(`${error.stack || error}\nCurrent URL: ${page.url()}\nHeadings: ${headings.join(' | ')}\nNavigations: ${navigationEvents.join(' -> ')}\nPage errors: ${pageErrors.join(' | ')}\nDialogs: ${JSON.stringify(dialogs)}\nLink: ${link}\nPage: ${body}`);
    }
    await page.getByText('<request>safe payload</request>').waitFor();
    assert.equal(await page.locator('.att-code-language').last().textContent(), 'XML');
    assert.equal(await page.getByText('template-secret').count(), 0, 'classified source values must remain redacted');

    await page.goBack();
    await page.getByRole('heading', { name: 'Reference Flow' }).waitFor();
    await page.locator('a[data-nav="configuration"]').click();
    await page.getByRole('heading', { name: 'Configuration', level: 1 }).waitFor();
    await page.getByRole('tab', { name: 'Source' }).click();
    await page.getByRole('treeitem', { name: /config/ }).click();
    await page.getByRole('treeitem', { name: /tools/ }).click();
    await page.getByRole('treeitem', { name: /att-helper\.sh/ }).click();
    await page.getByText('# Safe configuration helper source').waitFor();

    await page.getByRole('treeitem', { name: /config\.yaml/ }).click();
    const configSource = page.locator('.att-config-source-view .att-code-editor .cm-content');
    await configSource.getByText('testcase:', { exact: false }).waitFor();
    await page.getByText(/Safe read-only source/).waitFor();

    await page.getByRole('tab', { name: 'Testdata' }).click();
    await page.getByRole('heading', { name: 'Testdata' }).waitFor();
    await page.getByRole('button', { name: 'generatedAccounts' }).click();
    await page.getByRole('button', { name: 'Preview first 50 generated records' }).click();
    await page.getByRole('table').getByText('A-001000', { exact: true }).waitFor();

    await page.setViewportSize({ width: 768, height: 1024 });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.getByRole('button', { name: 'Open navigation' }).click();
    await page.locator('a[data-nav="explorer"]').click();
    await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
    await page.getByRole('button', { name: 'Reference Flow' }).click();
    await page.getByRole('button', { name: 'Quick Load' }).click();
    await page.getByRole('heading', { name: 'Quick Load' }).waitFor();
    const quickLoadTarget = page.locator('.att-quick-load-page .att-section-heading strong');
    await quickLoadTarget.waitFor();
    assert.match(await quickLoadTarget.textContent(), /^flow · /, 'Quick Load should retain the selected Flow target');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    assert.equal(overflow, false, 'the live Configuration and Quick Load UI must fit a tablet viewport');
    assert.deepEqual(pageErrors, [], 'the live ATT Studio UI must not throw browser errors');
    process.stdout.write('Packaged-WAR Playwright API smoke passed under /tools/att; source references, Configuration, Testdata, and Quick Load used the real Server API.\n');
  } finally {
    await context.close();
    await browser.close();
  }
}

main().catch(error => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
