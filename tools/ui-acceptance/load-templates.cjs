const assert = require('node:assert/strict');
const { launchBrowser, startFixture, stopFixture } = require('./browser-support.cjs');
(async () => {
 const fixture = await startFixture(); let browser;
 try {
  browser=await launchBrowser(); const page=await browser.newPage({ viewport:{width:1280,height:900} }); const errors=[];const calls=[];
  page.on('pageerror',error=>errors.push(error.message));page.on('request',request=>calls.push(request.url()));
  const base=fixture.origin+'/att/ui/';
  await page.goto(base+'#/advanced-load');await page.locator('.att-template-presets article').nth(5).waitFor();
  assert.equal(await page.locator('.att-template-presets article').count(),6);
  assert.equal(calls.filter(url=>url.includes('/load-catalog')||url.includes('/jobs?')).length,0,'Templates must not fetch legacy resources or jobs');
  await page.locator('.att-template-presets article').nth(5).getByRole('button',{name:'＋ Create Load',exact:true}).click();
  await page.getByRole('heading',{name:'Multi-workload Mix',level:1,exact:true}).waitFor();
  assert.match(await page.locator('body').innerText(),/2 Workloads · 4 Target bindings · 0 Unique targets/);
  await page.getByRole('button',{name:'⤨ Edit Mix',exact:true}).first().click();
  const dialog=page.getByRole('dialog',{name:'Edit Mix',exact:true});await dialog.waitFor();
  await dialog.getByRole('button',{name:'＋ Add Target',exact:true}).click();assert.equal(await dialog.locator('tbody tr').count(),3);
  await page.keyboard.press('Escape');await dialog.waitFor({state:'hidden'});
  assert.equal(await page.getByRole('button',{name:'▶ Run Now',exact:true}).isDisabled(),true);
  await page.getByRole('button',{name:'⤨ Edit Mix',exact:true}).first().click();assert.equal(await dialog.locator('tbody tr').count(),2);
  await dialog.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'⚙ Common Settings',exact:true}).click();
  const settings=page.getByRole('dialog',{name:'Common Settings',exact:true});await settings.waitFor();
  assert.ok(await settings.evaluate(element=>element.getBoundingClientRect().width)<=390);
  await settings.getByRole('button',{name:'Cancel',exact:true}).click();
  await page.screenshot({path:process.env.ATT_UI_SCREENSHOT || require('node:path').join(require('node:os').tmpdir(),'att-load-template-mobile.png'),fullPage:true});
  assert.equal(calls.filter(url=>url.includes('/jobs/load')||url.includes('/drafts/load')).length,0,'Editing never starts traffic or validation');
  assert.deepEqual(errors,[]);console.log('Load template non-root browser checks passed: six modes, no history fetch, nested modal Cancel/Escape, mobile bounds, no traffic.');
 } finally {if(browser)await browser.close();await stopFixture(fixture);}
})().catch(error=>{console.error(error);process.exitCode=1;});
