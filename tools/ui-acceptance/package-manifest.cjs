const assert = require('node:assert/strict');
const path = require('node:path');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture();
  let browser;
  let source = 'schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n';
  let digest = 'a'.repeat(64), draft, conflict = false, masked = false;
  const actions = [], errors = [];
  const projection = () => ({ packageId: 'payments', present: true, canEdit: true, manifestDigest: digest,
    environment: source.includes('UAT') ? 'UAT' : 'SIT', environmentOrigin: 'package',
    available: true, text: source, redacted: masked, declared: { schemaVersion: 'att-package/v1' },
    fields: { environment: { value: source.includes('UAT') ? 'UAT' : 'SIT', origin: 'package', authoredPath: 'package.yaml' } },
    globalFields: { environment: { value: 'SIT', origin: 'global', authoredPath: 'config/config.yaml' } } });
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    await selectFixture(context, fixture.origin, 'loaded');
    const page = await context.newPage();page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/v1/packages/payments/package-configuration/**', async route => {
      const request = route.request(), url = new URL(request.url());
      const action = url.pathname.split('/package-configuration/')[1];
      const send = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      if (request.method() === 'GET') return send(action === 'history' ? { items: [] } : projection());
      actions.push(action);
      if (action === 'drafts') {
        const input = request.postDataJSON();assert.equal(input.expectedManifestDigest, digest);
        draft = { draftId: 'M123', packageId: 'payments', state: 'draft', expectedManifestDigest: digest, expiresAt: '2030-01-01T00:00:00Z' };
        draft.source = input.source;return send(draft);
      }
      if (action.endsWith('/validate')) {
        draft.state = 'validated';draft.validation = { ...projection(), valid: true, environment: 'UAT' };return send(draft);
      }
      if (action.endsWith('/review')) { assert.equal(draft.state, 'validated');draft.state = 'reviewed';return send(draft); }
      if (action.endsWith('/publish')) {
        assert.equal(draft.state, 'reviewed');
        if (conflict) return send({ error: { code: 'CONFLICT', summary: 'Fixture source revision changed' } }, 409);
        source = draft.source;digest = 'b'.repeat(64);return send({ published: true, manifestDigest: digest, auditConfirmed: true });
      }
      if (action.endsWith('/discard')) { draft = null;return send({ discarded: true }); }
      throw new Error(`Unexpected Manifest request ${action}`);
    });
    await page.goto(`${fixture.origin}/att/ui/#/configuration/payments`);
    await page.getByRole('button', { name: 'Package Configuration', exact: true }).click();
    const workspace = page.getByRole('region', { name: 'Package Configuration', exact: true });
    await workspace.getByText('Resolved Environment:', { exact: false }).waitFor();
    assert.match(await workspace.innerText(), /Origin: package/);
    await workspace.getByRole('button', { name: 'Compare with Global', exact: true }).click();
    await workspace.getByRole('columnheader', { name: 'Global value' }).waitFor();
    await workspace.getByRole('button', { name: 'Source', exact: true }).click();
    const create = workspace.getByRole('button', { name: 'Create Manifest draft' });
    await create.waitFor();await page.waitForFunction(() => !!document.querySelector('.att-manifest-workspace .cm-content[contenteditable="true"]'));
    await workspace.locator('.cm-content[contenteditable="true"]').fill('schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n');
    await create.click();
    const validate = workspace.getByRole('button', { name: 'Validate Manifest draft' });
    const review = workspace.getByRole('button', { name: 'Review validated Manifest' });
    const publish = workspace.getByRole('button', { name: 'Publish reviewed Manifest' });
    await validate.waitFor();assert.equal(await review.isDisabled(), true);assert.equal(await publish.isDisabled(), true);
    await validate.click();await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent === 'Review validated Manifest' && !button.disabled));
    assert.equal(await publish.isDisabled(), true);await review.click();
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent === 'Publish reviewed Manifest' && !button.disabled));
    await publish.click();await workspace.getByText('Manifest published. Package views will refresh.', { exact: true }).waitFor();
    assert.deepEqual(actions, ['drafts', 'drafts/M123/validate', 'drafts/M123/review', 'drafts/M123/publish']);
    await workspace.getByRole('button', { name: 'Effective', exact: true }).click();
    await workspace.getByText('UAT', { exact: true }).first().waitFor();
    await page.screenshot({ fullPage: true, animations: 'disabled', path: path.join(process.env.ATT_UI_SCREENSHOT_DIR || '/tmp', 'att-package-manifest-desktop.png') });
    await page.setViewportSize({ width: 420, height: 900 });
    await page.screenshot({ fullPage: true, animations: 'disabled', path: path.join(process.env.ATT_UI_SCREENSHOT_DIR || '/tmp', 'att-package-manifest-mobile.png') });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'Manifest workspace must fit the mobile viewport');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await workspace.getByRole('button', { name: 'Source', exact: true }).click();
    await create.waitFor();await create.click();await validate.click();
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent === 'Review validated Manifest' && !button.disabled));
    await review.click();conflict = true;await publish.click();
    await workspace.getByRole('alert').filter({ hasText: 'Sources changed after validation' }).waitFor();
    assert.equal(await workspace.getByRole('button', { name: 'Discard Manifest draft' }).isVisible(), true);
    await workspace.getByRole('button', { name: 'Discard Manifest draft' }).click();
    masked = true;await workspace.getByRole('button', { name: 'Refresh Package Configuration' }).click();
    await workspace.getByText('Some source values are masked.', { exact: false }).waitFor();assert.equal(await create.isDisabled(), true);
    assert.deepEqual(errors, []);console.log('Package Manifest browser workflow passed: views, gated publish, refresh, conflict, masking, mobile layout.');
  } finally { if (browser) await browser.close();await stopFixture(fixture); }
}
main().catch(error => { console.error(error);process.exitCode = 1; });
