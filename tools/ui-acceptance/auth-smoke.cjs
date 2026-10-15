const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

async function checkAccessibility(page, message) {
  await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
  const audit = await page.evaluate(async () => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  }));
  assert.deepEqual(audit.violations.map(({ id, impact, nodes }) => ({ id, impact, selectors: nodes.map(node => node.target) })), [], message);
}

async function main() {
  const fixture = await startFixture();
  let browser;
  let traditionalContext;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, bypassCSP: true });
    const page = await context.newPage();
    const pageErrors = [];
    let userListRequests = 0;
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('request', request => { if (request.url().includes('/api/v1/auth/users')) userListRequests += 1; });
    const base = `${fixture.origin}/att/ui/`;

    await selectFixture(context, fixture.origin, 'auth-admin');
    const returnToLogin = page => page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.route(`${fixture.origin}/att/api/v1/auth/login`, route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ authenticated: true }),
    }));
    const deepLink = '/ui/#/jobs/JOB-9?view=events';
    await page.goto(`${base}login.html?returnTo=${encodeURIComponent(deepLink)}`);
    await page.getByLabel('Login ID', { exact: true }).fill('admin');
    await page.getByLabel('Password', { exact: true }).fill('fixture-password');
    await returnToLogin(page);
    await page.waitForURL(url => url.pathname === '/att/ui/' && url.hash === '#/jobs/JOB-9?view=events');
    await page.goto(`${base}login.html?returnTo=${encodeURIComponent('//attacker.example/path')}`);
    await page.getByLabel('Login ID', { exact: true }).fill('admin');
    await page.getByLabel('Password', { exact: true }).fill('fixture-password');
    await returnToLogin(page);
    await page.waitForURL(url => url.pathname === '/att/ui/' && !url.hash);
    await page.goto(`${base}login.html?returnTo=${encodeURIComponent('/ui/%252e%252e/admin/')}`);
    await page.getByLabel('Login ID', { exact: true }).fill('admin');
    await page.getByLabel('Password', { exact: true }).fill('fixture-password');
    await returnToLogin(page);
    await page.waitForURL(url => url.pathname === '/att/ui/' && !url.hash);
    await page.unroute(`${fixture.origin}/att/api/v1/auth/login`);
    await page.goto(`${base}#/settings`);
    await page.getByRole('heading', { name: 'User Management', exact: true }).waitFor();
    await page.getByRole('row', { name: /jane Jane Tester USER Active/ }).waitFor();
    const search = page.getByRole('searchbox', { name: 'Search users' });
    await search.fill('jane');
    assert.equal(await page.locator('.att-user-table tbody tr').count(), 1, 'search should filter the safe user list');
    await search.fill('');

    await page.getByRole('button', { name: 'Create User', exact: true }).click();
    let dialog = page.getByRole('dialog');
    await dialog.getByLabel('Login ID').fill('new-user');
    await dialog.getByLabel('Display Name').fill('New User');
    await dialog.getByLabel('Role').selectOption('USER');
    await dialog.getByLabel('New password', { exact: true }).fill('new user secure password');
    await dialog.getByLabel('Confirm new password', { exact: true }).fill('new user secure password');
    await dialog.getByRole('button', { name: 'Create user', exact: true }).click();
    await page.getByRole('row', { name: /new-user New User USER Active/ }).waitFor();

    await page.getByRole('button', { name: 'Create User', exact: true }).click();
    dialog = page.getByRole('dialog');
    await dialog.getByLabel('Login ID').fill('jane');
    await dialog.getByLabel('Display Name').fill('Duplicate User');
    await dialog.getByLabel('New password', { exact: true }).fill('duplicate secure password');
    await dialog.getByLabel('Confirm new password', { exact: true }).fill('duplicate secure password');
    await dialog.getByRole('button', { name: 'Create user', exact: true }).click();
    await dialog.getByRole('alert').getByText('That Login ID is already in use.').waitFor();
    await dialog.locator('.dialog-actions').getByRole('button', { name: 'Cancel', exact: true }).click();

    await page.getByRole('button', { name: 'Reset password: jane' }).click();
    dialog = page.getByRole('dialog');
    await dialog.getByText('The new password replaces the existing one and revokes all active sessions for this user.').waitFor();
    await dialog.getByLabel('New password', { exact: true }).fill('reset jane secure password');
    await dialog.getByLabel('Confirm new password', { exact: true }).fill('reset jane secure password');
    await dialog.getByRole('button', { name: 'Confirm', exact: true }).click();
    await page.getByRole('status').getByText('Password reset. The user must sign in again.').waitFor();

    await page.getByRole('button', { name: 'Disable: jane' }).click();
    dialog = page.getByRole('dialog');
    await dialog.getByText('This prevents sign-in and revokes all active sessions for this user.').waitFor();
    await dialog.getByRole('button', { name: 'Confirm', exact: true }).click();
    await page.getByRole('row', { name: /jane Jane Tester USER Disabled/ }).waitFor();
    await checkAccessibility(page, 'Local User Management should pass the configured WCAG checks');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(100);
    const phoneWidth = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth }));
    assert.equal(phoneWidth.scrollWidth, phoneWidth.clientWidth, `User Management should not create horizontal page overflow on a phone: ${JSON.stringify(phoneWidth)}`);

    await selectFixture(context, fixture.origin, 'auth-user');
    userListRequests = 0;
    await page.reload();
    await page.getByRole('heading', { name: 'Settings', exact: true }).waitFor();
    const userManagementHeadings = await page.getByRole('heading', { name: 'User Management' }).count();
    assert.equal(userManagementHeadings, 0, `USER must not see User Management; requests=${userListRequests}; settings=${await page.locator('#main-content').innerText()}`);
    assert.equal(userListRequests, 0, 'USER UI must not fetch account data');
    const forbidden = await page.evaluate(async () => {
      const response = await fetch('../api/v1/auth/users', { credentials: 'same-origin' });
      return { status: response.status, body: await response.json() };
    });
    assert.equal(forbidden.status, 403, 'direct USER account-list access must remain forbidden');
    assert.equal(forbidden.body.error.code, 'ATT-SERVER-FORBIDDEN');

    const accountMenu = page.getByRole('button', { name: 'Change password', exact: true });
    await page.locator('.att-current-user-menu summary').click();
    await accountMenu.waitFor();
    await accountMenu.click();
    dialog = page.getByRole('dialog');
    await dialog.getByLabel('Current password', { exact: true }).fill('current secure password');
    await dialog.getByLabel('New password', { exact: true }).fill('changed secure password');
    await dialog.getByLabel('Confirm new password', { exact: true }).fill('changed secure password');
    await dialog.getByRole('button', { name: 'Change password', exact: true }).click();
    await page.getByText('Password changed. Sign in again with your new password.').waitFor();
    assert.match(page.url(), /login\.html\?message=password-changed$/);
    assert.equal(await page.evaluate(() => Object.keys(localStorage).concat(Object.keys(sessionStorage)).some(key => /password|credential|secret/i.test(key))), false,
      'password forms must not persist values in browser storage');

    await selectFixture(context, fixture.origin, 'auth-container');
    await page.goto(`${base}#/settings`);
    await page.getByText('ATT account controls are unavailable because credentials are managed by Tomcat or your SSO provider.').waitFor();
    assert.equal(await page.getByRole('button', { name: 'Change password', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Create User', exact: true }).count(), 0);

    traditionalContext = await browser.newContext({ locale: 'zh-Hant-HK', viewport: { width: 1280, height: 900 }, bypassCSP: true });
    const traditionalPage = await traditionalContext.newPage();
    await selectFixture(traditionalContext, fixture.origin, 'auth-admin');
    await traditionalPage.goto(`${base}#/settings`);
    await traditionalPage.getByRole('heading', { name: '使用者管理', exact: true }).waitFor();
    await traditionalPage.getByRole('button', { name: '建立使用者', exact: true }).waitFor();

    assert.deepEqual(pageErrors, [], 'auth UI flows should not report uncaught browser errors');
    console.log('ATT local authentication UI browser checks passed.');
  } finally {
    if (traditionalContext) await traditionalContext.close();
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => { console.error(error);process.exitCode = 1; });
