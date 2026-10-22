const fs = require('node:fs/promises');
const path = require('node:path');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

const repositoryRoot = path.resolve(__dirname, '../..');
const outputDirectory = path.join(repositoryRoot, 'docs/system-design/ui-shell-baseline');

async function main() {
  await fs.mkdir(outputDirectory, { recursive: true });
  const fixture = await startFixture();
  let browser;
  try {
    browser = await launchBrowser();
    const captures = [];
    const screens = [
      {
        name: 'dashboard', fixture: 'loaded', route: '',
        wait: async page => {
          await page.getByRole('region', { name: 'Dashboard summary', exact: true }).waitFor();
          if (await page.locator('.summary-card').count() !== 6) throw new Error('The Dashboard summary cards did not render.');
          await page.locator('.summary-card').filter({ hasText: 'Configured packages' })
            .getByText('2', { exact: true }).waitFor();
          await page.locator('#jobs-state').getByText(/recent job row/i).waitFor();
          await page.getByRole('list', { name: 'Recent jobs' }).getByText('JOB-4821', { exact: true }).waitFor();
        },
      },
      {
        name: 'packages', fixture: 'loaded', route: '#/packages',
        wait: async page => {
          await page.getByRole('heading', { name: 'Explorer', exact: true }).waitFor();
          await page.locator('.package-list li').first().waitFor();
          if (await page.locator('.package-list li').count() !== 2) throw new Error('The fixture packages did not load.');
        },
      },
      {
        name: 'explorer-list', fixture: 'explorer-workspace',
        route: '#/packages/payments',
        wait: async page => {
          await page.getByRole('heading', { name: 'Package Explorer' }).waitFor();
          await page.locator('.att-explorer-table tbody tr').first().waitFor();
          await page.getByText(/sorting applies to all matching resources/i).waitFor();
        },
      },
      {
        name: 'explorer-debug', fixture: 'explorer-workspace',
        route: '#/packages/payments/resources/template/checkout',
        wait: async page => {
          await page.getByRole('heading', { name: 'Resource details', level: 1, exact: true }).waitFor();
          await page.getByRole('heading', { name: 'checkout' }).waitFor();
          await page.locator('.att-code-editor[aria-label="Safe resource definition"] .cm-line')
            .filter({ hasText: '"name"' }).waitFor();
        },
      },
      {
        name: 'configuration', fixture: 'explorer-workspace',
        route: '#/configuration/payments?tab=source&file=config%2Fapplication.yaml',
        wait: async page => {
          await page.getByRole('heading', { name: 'Configuration', exact: true }).waitFor();
          await page.getByText('Safe read-only source').waitFor();
          await page.getByText('environment: dev', { exact: false }).waitFor();
          await page.locator('.att-config-source-view .cm-line').filter({ hasText: 'environment:' }).waitFor();
        },
      },
      {
        name: 'quick-load', fixture: 'explorer-workspace',
        route: '#/quick-load/payments/template/checkout',
        wait: async page => {
          await page.getByRole('heading', { name: 'Quick Load', exact: true }).waitFor();
          await page.getByText(/package policy or Server fallback is loaded/i).waitFor();
          await page.getByText('template · checkout', { exact: true }).waitFor();
        },
      },
      {
        name: 'load-builder', fixture: 'load-workspace', route: '#/advanced-load/payments',
        wait: async page => {
          await page.getByRole('heading', { name: 'New Load scenario' }).waitFor();
          await page.getByRole('status').getByText('Policy defaults loaded. Server validation is required before starting.', { exact: true }).waitFor();
        },
      },
      {
        name: 'job-results', fixture: 'load-workspace', route: '#/jobs/JOB-LOAD-204',
        wait: async page => {
          await page.getByRole('heading', { name: 'Job JOB-LOAD-204' }).waitFor();
          await page.getByRole('tab', { name: 'Results', exact: true }).click();
          await page.getByText(/Server result returned/i).waitFor();
        },
      },
      {
        name: 'job-console-floating', fixture: 'load-workspace', route: '#/jobs/JOB-LOAD-204',
        wait: async page => {
          await page.getByRole('heading', { name: 'Job JOB-LOAD-204' }).waitFor();
          await page.getByRole('button', { name: 'Open floating console' }).click();
          const floating = page.locator('[aria-label="Floating console for Job JOB-LOAD-204"]');
          await floating.getByRole('heading', { name: 'Job JOB-LOAD-204', exact: true }).waitFor();
          await floating.getByRole('tab', { name: 'Live Output', exact: true }).waitFor();
          await floating.getByRole('tab', { name: 'Events', exact: true }).waitFor();
          await floating.getByRole('tab', { name: 'Events', exact: true }).click();
          await floating.locator('.att-job-events li').first().waitFor();
          await floating.getByRole('tab', { name: 'Live Output', exact: true }).click();
          await floating.getByText('This Job has no console lines. Structured events remain on the Events tab.', { exact: true }).waitFor();
        },
      },
      {
        name: 'scheduled-load', fixture: 'scheduled-workspace', route: '#/load-schedules',
        wait: async page => {
          await page.getByRole('heading', { name: 'Schedules', level: 1, exact: true }).waitFor();
          await page.locator('.att-schedule-table-wrap:visible, .att-schedule-cards:visible')
            .getByText('Nightly payments', { exact: true }).waitFor();
        },
      },
    ];
    for (const screenName of [...screens.map(screen => screen.name), 'settings']) {
      for (const width of [1440, 768]) {
        for (const theme of ['light', 'dark']) {
          await fs.rm(path.join(outputDirectory, `${screenName}-${width}-${theme}.png`), { force: true });
        }
      }
    }
    for (const screen of screens) {
      for (const width of [1440]) {
        for (const theme of ['light', 'dark']) {
          const context = await browser.newContext({
            viewport: { width, height: 1000 },
            colorScheme: theme,
            locale: 'en-US',
          });
          await selectFixture(context, fixture.origin, screen.fixture);
          await context.addInitScript(({ theme, language }) => {
            localStorage.setItem('att-ui-theme', theme);
            localStorage.setItem('att-ui-language', language);
          }, { theme, language: 'en' });
          const page = await context.newPage();
          const pageErrors = [];
          page.on('pageerror', error => pageErrors.push(error.message));
          await page.goto(`${fixture.origin}/att/ui/${screen.route}`);
          await screen.wait(page);
          const layout = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, viewportWidth: window.innerWidth }));
          if (layout.scrollWidth > width) throw new Error(`${screen.name} overflows at ${width}px: ${JSON.stringify(layout)}`);
          if (pageErrors.length > 0) throw new Error(`${screen.name} reported browser errors: ${pageErrors.join('; ')}`);
          const fileName = `${screen.name}-${width}-${theme}.png`;
          const filePath = path.join(outputDirectory, fileName);
          await page.screenshot({ path: filePath, fullPage: false, animations: 'disabled' });
          captures.push({
            file: fileName,
            screen: screen.name,
            viewport: { width, height: 1000 },
            theme,
            fixture: `${screen.fixture} synthetic API data`,
            page: `/att/ui/${screen.route}`,
          });
          await context.close();
        }
      }
    }
    await fs.writeFile(path.join(outputDirectory, 'manifest.json'), `${JSON.stringify({
      generatedAt: new Date().toISOString(),
      repositoryVersion: '4.2.2',
      browser: await browser.version(),
      playwright: require('playwright/package.json').version,
      fixtureOrigin: fixture.origin,
      captures,
    }, null, 2)}\n`);
    console.log(`Wrote ${captures.length} phase screenshots to ${outputDirectory}`);
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
