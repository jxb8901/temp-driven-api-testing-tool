const assert = require('node:assert/strict');
const {launchBrowser,startFixture,stopFixture} = require('./browser-support.cjs');
async function main() {
  const fixture=await startFixture(), browser=await launchBrowser();
  try {
    const context=await browser.newContext({viewport:{width:1440,height:900}}), page=await context.newPage();
    const queries=[], errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(() => { if (!sessionStorage.getItem('att.workspace.context')) sessionStorage.setItem('att.workspace.context', JSON.stringify({packageId:'payments',environment:'UAT'})); });
    await context.route('**/api/v1/**', async route => {
      const url=new URL(route.request().url()), path=decodeURIComponent(url.pathname.split('/api/v1/')[1]||'');
      const json=body=>route.fulfill({json:body});
      if(path==='packages')return json({items:[{packageId:'payments'},{packageId:'ledger'}]});
      if(/^packages\/[^/]+\/configuration$/.test(path))return json({state:'ready',environments:[{name:'UAT',state:'active'},{name:'SIT',state:'active'}]});
      if(/^packages\/[^/]+\/package-configuration\/effective$/.test(path))return json({environment:url.searchParams.get('environment')||'SIT',environmentOrigin:url.searchParams.has('environment')?'request':'package'});
      if(path==='jobs') {queries.push(Object.fromEntries(url.searchParams));return json({items:[],total:0,offset:0,limit:100,groups:[],principalSuggestions:[],packageSuggestions:['payments','ledger']});}
      if(path==='jobs/J1234567890ABCDEF')return json({jobId:'J1234567890ABCDEF',packageId:'ledger',environment:'SIT',command:'run',executionType:'run',status:'PASS',targetIdentity:{type:'flow',id:'ledger-flow'}});
      if(path==='jobs/J1234567890ABCDEF/result')return json({job:{jobId:'J1234567890ABCDEF',status:'PASS'},result:{}});
      if(path==='jobs/J1234567890ABCDEF/replay')return json({jobId:'J1234567890ABCDEF',available:false});
      if(path==='jobs/J1234567890ABCDEF/artifacts')return json({items:[]});
      return route.fallback();
    });
    const base=fixture.origin+'/att/ui/';
    await page.goto(base+'#/jobs/J1234567890ABCDEF');
    const pkg=page.getByLabel('Workspace Package'), env=page.getByLabel('Workspace Environment');
    await page.getByText('Executed Environment',{exact:true}).waitFor();
    assert.equal(await pkg.inputValue(),'payments');assert.equal(await env.inputValue(),'UAT');
    assert.equal(await pkg.isDisabled(),true);assert.equal(await env.isDisabled(),true);
    assert.equal(await page.locator('dt').filter({hasText:'Executed Environment'}).locator('..').locator('dd').innerText(),'SIT');
    await page.reload();await page.getByText('Executed Environment',{exact:true}).waitFor();
    assert.equal(await pkg.inputValue(),'payments');assert.equal(await env.inputValue(),'UAT');
    await page.goto(base+'#/jobs');await page.getByLabel('Jobs list scope').waitFor();
    await page.waitForFunction(() => document.querySelector('[aria-label="Workspace Package"]') && !document.querySelector('[aria-label="Workspace Package"]').disabled);
    await page.waitForTimeout(300);assert.ok(queries.some(q=>q.packageId==='payments'&&q.environment==='UAT'));
    await page.getByLabel('Jobs list scope').selectOption('all');await pkg.selectOption('ledger');
    await page.waitForTimeout(300);assert.equal(await env.inputValue(),'');assert.equal(queries.at(-1).packageId,undefined);
    await page.getByLabel('Jobs list scope').selectOption('follow');await page.waitForTimeout(300);assert.equal(queries.at(-1).packageId,'ledger');assert.equal(queries.at(-1).environment,'SIT');
    const second=await context.newPage();await second.goto(base+'#/jobs');await second.getByLabel('Workspace Package').waitFor();
    assert.equal(await second.getByLabel('Workspace Package').inputValue(),'');
    await second.getByLabel('Workspace Package').selectOption('payments');assert.equal(await pkg.inputValue(),'ledger');
    await second.close();
    await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
    assert.deepEqual(errors,[]);console.log('Workspace context isolation and list scope browser checks passed.');
  } finally {await browser.close();await stopFixture(fixture);}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
