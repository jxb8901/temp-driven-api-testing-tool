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
    for (const width of [1440, 768]) {
      for (const theme of ['light', 'dark']) {
        const context = await browser.newContext({
          viewport: { width, height: 1000 },
          colorScheme: theme,
        });
        await selectFixture(context, fixture.origin, 'loaded');
        const page = await context.newPage();
        await page.addInitScript(value => localStorage.setItem('att-ui-theme', value), theme);
        await page.goto(`${fixture.origin}/att/ui/`);
        await page.locator('#packages-state').getByText(/configured package/i).waitFor();
        await page.locator('#jobs-state').getByText(/recent job row/i).waitFor();
        const fileName = `dashboard-${width}-${theme}.png`;
        const filePath = path.join(outputDirectory, fileName);
        await page.screenshot({ path: filePath, fullPage: false, animations: 'disabled' });
        captures.push({
          file: fileName,
          viewport: { width, height: 1000 },
          theme,
          fixture: 'loaded synthetic package and job rows',
          page: '/att/ui/',
        });
        await context.close();
      }
    }
    await fs.writeFile(path.join(outputDirectory, 'manifest.json'), `${JSON.stringify({
      generatedAt: new Date().toISOString(),
      repositoryVersion: '4.1.3',
      browser: await browser.version(),
      playwright: require('playwright/package.json').version,
      fixtureOrigin: fixture.origin,
      captures,
    }, null, 2)}\n`);
    console.log(`Wrote ${captures.length} dashboard baselines to ${outputDirectory}`);
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
