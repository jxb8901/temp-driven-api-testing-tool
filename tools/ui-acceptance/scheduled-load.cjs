const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

async function assertAxe(page, message) {
  const audit = await page.evaluate(async () => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
  }));
  assert.deepEqual(audit.violations.map(({ id, impact, help, nodes }) => ({
    id, impact, help, selectors: nodes.map(node => node.target),
  })), [], message);
}

async function waitForScheduleList(page) {
  await page.waitForFunction(() => {
    const status = document.querySelector('.att-schedule-status');
    return status !== null && status.getAttribute('aria-busy') === 'false';
  });
}

async function main() {
  const fixture = await startFixture();
  let browser;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ viewport: { width: 1280, height: 980 }, colorScheme: 'dark', bypassCSP: true });
    const page = await context.newPage();
    const pageErrors = [];
    const scheduleRequests = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('request', request => {
      const path = new URL(request.url()).pathname;
      if (path.includes('/api/v1/schedules') || path.includes('/api/v1/load-schedules')) {
        scheduleRequests.push(`${request.method()} ${path}`);
      }
    });
    await selectFixture(context, fixture.origin, 'scheduled-workspace');
    const base = `${fixture.origin}/att/ui/`;
    await page.goto(`${base}#/load-schedules`);
    await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
    await page.getByText('Nightly payments', { exact: true }).first().waitFor();
    assert.equal(await page.locator('[data-nav="scheduled-load"]').getAttribute('aria-current'), 'page');
    assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'dark', 'the UI should follow the OS dark preference until a user choice is stored');

    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    await assertAxe(page, 'Schedules should pass WCAG 2.2 AA checks');

    await page.getByRole('button', { name: 'History Nightly payments' }).first().click();
    await page.getByText(/Skipped because this schedule already reached its concurrent-run limit/).waitFor();
    const submittedRow = page.locator('.att-occurrence-table tbody tr', { hasText: 'O-SUBMITTED' });
    await submittedRow.getByText('Job Passed', { exact: true }).waitFor();
    assert.equal(await submittedRow.locator('.att-schedule-job-state.is-enabled').count(), 1, 'a retained job shows its own outcome next to the submission status');
    const expiredRow = page.locator('.att-occurrence-table tbody tr', { hasText: 'O-EXPIRED' });
    await expiredRow.getByText('JOB-4477 · no longer available', { exact: true }).waitFor();
    assert.equal(await expiredRow.getByRole('button', { name: 'JOB-4477', exact: true }).count(), 0, 'an expired job record must stay non-navigable');
    await page.getByRole('button', { name: 'JOB-4821', exact: true }).click();
    assert.match(page.url(), /#\/jobs\/JOB-4821$/);
    await page.goto(`${base}#/load-schedules`);

    await page.getByRole('button', { name: 'New Schedule' }).click();
    await page.locator('#schedule-name').fill('Automated UI schedule');
    await page.locator('#schedule-package').selectOption('payments');
    await page.locator('#schedule-scenario').fill('load/ui.yaml');
    await page.locator('#schedule-timezone').fill('America/New_York');
    await page.locator('#schedule-recurrence').selectOption('weekly');
    await page.locator('#schedule-time').fill('09:30');
    await page.locator('#schedule-weekday').selectOption('1-5');
    await page.getByLabel('Enable after saving').check();
    await page.getByRole('button', { name: 'Save schedule' }).waitFor({ state: 'visible' });
    await page.waitForFunction(() => {
      const button = [...document.querySelectorAll('button')].find(item => item.textContent.trim() === 'Save schedule');
      return button && !button.disabled;
    });
    await page.getByRole('button', { name: 'Save schedule' }).click();
    await page.getByText('Automated UI schedule', { exact: true }).first().waitFor();
    // This fixture advertises no unified RUN capability, so the client must stay on the legacy routes.
    assert.ok(scheduleRequests.some(request => request.startsWith('POST') && request.endsWith('/load-schedules/preview')));
    assert.ok(scheduleRequests.some(request => request === 'POST /att/api/v1/load-schedules'));

    await page.getByRole('button', { name: 'Pause Automated UI schedule' }).first().click();
    await page.getByRole('button', { name: 'Confirm state change' }).click();
    await page.getByRole('button', { name: 'Resume Automated UI schedule' }).first().waitFor();
    await page.getByRole('button', { name: 'Resume Automated UI schedule' }).first().click();
    await page.getByRole('button', { name: 'Confirm state change' }).click();
    await page.getByRole('button', { name: 'Edit Automated UI schedule' }).first().click();
    await page.locator('#schedule-description').fill('Updated weekly run');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await waitForScheduleList(page);

    await page.getByRole('button', { name: 'Run Now Automated UI schedule' }).first().click();
    await page.getByRole('heading', { name: 'Run this schedule now?' }).waitFor();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert.equal(scheduleRequests.filter(request => request.endsWith('/SCHED-CREATED/trigger')).length, 0, 'cancel should not start a Load job');
    await page.getByRole('button', { name: 'Run Now Automated UI schedule' }).first().click();
    await page.getByRole('button', { name: 'Confirm Run Now' }).click();
    await page.waitForURL('**/#/schedules/SCHED-CREATED/history');
    await page.getByText('O-NOW', { exact: true }).waitFor();
    await page.locator('.att-occurrence-table tbody tr', { hasText: 'O-NOW' }).getByText('Job Queued', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'JOB-4821', exact: true }).last().click();
    assert.match(page.url(), /#\/jobs\/JOB-4821$/);
    await page.goto(`${base}#/load-schedules`);

    await page.getByRole('button', { name: 'Delete Automated UI schedule' }).first().click();
    await page.getByRole('heading', { name: 'Delete this schedule?' }).waitFor();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert.ok(await page.getByText('Automated UI schedule', { exact: true }).count());
    await page.getByRole('button', { name: 'Delete Automated UI schedule' }).first().click();
    await page.getByRole('button', { name: 'Confirm delete' }).click();
    await page.getByText('Deleted Automated UI schedule.', { exact: false }).waitFor();

    await page.keyboard.press('Control+k');
    await page.getByRole('dialog').getByLabel('Search pages').fill('jobs');
    await page.getByRole('button', { name: /Jobs and results/ }).click();
    await page.getByRole('heading', { name: 'Jobs', exact: true }).waitFor();
    await page.keyboard.press('?');
    await page.getByRole('heading', { name: 'Keyboard shortcuts' }).waitFor();
    await page.keyboard.press('Escape');
    await page.getByRole('dialog').waitFor({ state: 'hidden' });

    await page.goto(`${base}#/load-schedules`);
    await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
    for (const width of [1280, 768, 390]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(80);
      const layout = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        viewport: window.innerWidth,
        overflow: [...document.querySelectorAll('body *')].map(element => ({
          tag: element.tagName.toLowerCase(), className: typeof element.className === 'string' ? element.className : '',
          text: element.textContent?.trim().slice(0, 30), right: Math.round(element.getBoundingClientRect().right),
        })).filter(item => item.right > window.innerWidth + 1).slice(0, 8),
      }));
      assert.ok(layout.scrollWidth <= width, `Schedules must not overflow at ${width}px: ${JSON.stringify(layout)}`);
      if (width <= 820) assert.equal(await page.locator('.att-schedule-cards').isVisible(), true, `schedule cards should be visible at ${width}px`);
    }
    await page.getByRole('button', { name: 'Switch to light theme' }).click();
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
    assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'light', 'an explicit theme should persist when the OS preference changes');

    await selectFixture(context, fixture.origin, 'schedule-forbidden');
    await page.reload();
    await waitForScheduleList(page);
    assert.match(await page.locator('#main-content').innerText(), /administrator role/);
    await selectFixture(context, fixture.origin, 'schedule-unauthenticated');
    await page.reload();
    await waitForScheduleList(page);
    assert.match(await page.locator('#main-content').innerText(), /authenticated/);
    await selectFixture(context, fixture.origin, 'schedule-disabled');
    await page.reload();
    await page.getByText(/Schedules is disabled by Server configuration/).waitFor();

    assert.deepEqual(pageErrors, [], 'Schedules should not report uncaught browser errors');
    console.log('ATT Schedules browser acceptance passed.');
    await context.close();
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
