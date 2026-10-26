const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture(); const browser = await launchBrowser();
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    context.setDefaultTimeout(15000);
    await selectFixture(context,fixture.origin,'job-replay');
    const fixtureJobs=await fetch(fixture.origin+'/att/api/v1/jobs').then(response=>response.json());
    assert.ok(fixtureJobs.items.some(job=>job.jobId==='J0000000000000002'));
    const fixtureDraft=await fetch(fixture.origin+'/att/api/v1/jobs/J0000000000000002/replay-draft',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sourcePolicy:'current',overrides:{load:{duration:'24h'}}})});
    const fixtureDraftBody=await fixtureDraft.json();assert.equal(fixtureDraft.status,201);assert.equal(fixtureDraftBody.schemaVersion,'att-job-replay-draft/v1');
    const fixturePrefill=await fetch(fixture.origin+`/att/api/v1/jobs/J0000000000000002/replay-draft/${fixtureDraftBody.draftRef}`).then(response=>response.json());
    assert.equal(fixturePrefill.definition.scenario.load.duration,'24h');assert.equal(fixturePrefill.definition.scenario.workloads.length,2);
    const staleFixture=await fetch(fixture.origin+'/att/api/v1/jobs/J0000000000000002/rerun',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirm:true,intentId:fixtureDraftBody.intentId,sourcePolicy:'current'})});
    assert.equal(staleFixture.status,409);assert.equal((await staleFixture.json()).error.code,'ATT-SERVER-REPLAY-DRAFT-STALE');
    const readyFixtureDraft=await fetch(fixture.origin+'/att/api/v1/jobs/J0000000000000002/replay-draft',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sourcePolicy:'current'})}).then(response=>response.json());
    const fixtureAccepted=await fetch(fixture.origin+'/att/api/v1/jobs/J0000000000000002/rerun',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirm:true,intentId:readyFixtureDraft.intentId,sourcePolicy:'current'})}).then(response=>response.json());
    const fixtureRetry=await fetch(fixture.origin+'/att/api/v1/jobs/J0000000000000002/rerun',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirm:true,intentId:readyFixtureDraft.intentId,sourcePolicy:'current'})}).then(response=>response.json());
    assert.equal(fixtureRetry.jobId,fixtureAccepted.jobId,'Same confirmation intent returns the accepted Job.');
    const ids = { run:'J0000000000000001', load:'J0000000000000002', old:'J0000000000000003' };
    const jobs = new Map(['run', 'load'].map(type => [ids[type], { jobId: ids[type], command: type, executionType: type, packageId: 'payments', principal: 'alice', status: 'PASS' }]));
    jobs.set(ids.old, { jobId: ids.old, command: 'load', packageId: 'payments', status: 'PASS' });
    const jobDefinitions = new Map();
    const submits = [], saves = [], broadRequests = [], replayDraftRequests = []; let schedules = []; let sequence = 0, draftSequence=0, staleLoadSubmit=false, staleLoadUsed=false;
    const replayDrafts = new Map();
    const originalDefinition = id => id === ids.run
      ? { executionType:'run',packageId:'payments',environment:'SIT',schemaVersion:'att-execution/v1',target:{type:'tool',id:'lookup-customer'},input:{inputs:{requestId:'R1'},arguments:{limit:3}},options:{debug:true,failFast:true} }
      : { executionType:'load',packageId:'payments',environment:'SIT',sourcePath:'load/nightly.yaml',scenario:{schemaVersion:'att-load/v1.9',load:{warmup:'2s',rampUp:'3s',duration:'30m',rampDown:'4s'},workloads:[
          {id:'checkout-main',target:{type:'template',id:'checkout'},inputs:{region:'HK'},load:{users:4},thresholds:{p95:'< 800ms'}},
          {id:'checkout-mix',mix:[{id:'purchase',weight:100,target:{type:'flow',id:'purchase-flow'}}],load:{users:2}},
        ],testdata:['examples/accounts.yaml'],thresholds:{minThroughput:'>= 10/s'},evidence:{mode:'failures',maxFailureSamples:5}} };
    const definitionFor = id => jobDefinitions.get(id) || originalDefinition(id);
    const makeReplayDraft = (id, body) => {
      const definition=JSON.parse(JSON.stringify(definitionFor(id))),overrides=body.overrides||{};
      if(definition.scenario){
        if(overrides.environment)definition.environment=overrides.environment;else if(overrides.environment==='')delete definition.environment;
        Object.assign(definition.scenario.load||{},overrides.load||{});
        for(const patch of overrides.workloads||[]){const row=definition.scenario.workloads.find(item=>item.id===patch.id);if(row){Object.assign(row.load,patch.load||{});for(const field of ['execution','target','mix','inputs','vars','testdata','thresholds','evidence'])if(patch[field]!==undefined)row[field]=patch[field];}}
        for(const field of ['execution','testdata','thresholds','evidence','seed'])if(overrides[field]!==undefined)definition.scenario[field]=overrides[field];
      }else{
        if(overrides.environment)definition.environment=overrides.environment;else if(overrides.environment==='')delete definition.environment;
        Object.assign(definition.options,overrides.options||{});if(overrides.target)definition.target=overrides.target;if(overrides.selection)definition.selection=overrides.selection;if(overrides.input)definition.input=overrides.input;
      }
      const prefix=definition.executionType==='run'?'R':'C',code=(++draftSequence).toString(16).toUpperCase().padStart(32,'0');
      const draftRef=prefix+code,draftId=draftRef,intentId=`00000000-0000-4000-8000-${draftSequence.toString(16).padStart(12,'0')}`;
      const expiresAt='2099-01-01T00:00:00Z';
      const item={schemaVersion:'att-job-replay-draft/v1',draftRef,draftId,intentId,sourceJobId:id,packageId:'payments',executionType:definition.executionType,
        sourcePolicy:body.sourcePolicy||'strict',originalRevision:'a'.repeat(64),currentRevision:'b'.repeat(64),revision:'b'.repeat(64),definition,
        originalConfiguration:{packageId:'payments',environment:'SIT',packageRevision:'a'.repeat(64)},
        configuration:{packageId:'payments',environment:definition.environment||'dev'},sourcePath:definition.sourcePath,expiresAt,
        preview:{draftId,packageId:'payments',model:'virtualUsers',previewYaml:JSON.stringify(definition.scenario||definition,null,2),expiresAt,redacted:false,diagnostics:[]}};
      replayDrafts.set(draftRef,item);replayDraftRequests.push({jobId:id,body});
      if(id===ids.load&&overrides.load?.duration==='24h'&&!staleLoadUsed){staleLoadSubmit=true;staleLoadUsed=true;}
      return item;
    };
    const nextRunAt = '2099-01-01T02:00:00Z';
    await context.route('**/api/v1/**', async route => {
      const request = route.request(), method = request.method(), url = new URL(request.url());
      const path = decodeURIComponent(url.pathname.split('/api/v1/')[1] || '');
      if (path === 'jobs' || path === 'packages') broadRequests.push(path);
      const json = (body, status = 200) => route.fulfill({ status, json: body });
      if (path === 'version') return json({ version: '4.2.5', apiVersion: '1', inlineLoadEnabled: true, loadSchedulingEnabled: true, unifiedRunVersion: 'att-execution/v1' });
      if (path === 'packages') return json({ items: [{ packageId: 'payments', name: 'Payments' }] });
      if (path === 'packages/payments/configuration') return json({ view: 'declared', environments: [{ name: 'SIT', state:'active' }] });
      if (path === 'packages/payments/load-catalog') return json({ items: [], total: 0 });
      if (path === 'packages/payments/load-policy') return json({model:url.searchParams.get('model')||'virtualUsers',policy:{schemaVersion:'att-load/v1.9',load:{users:1,warmup:'1s',rampUp:'1s',duration:'10s',rampDown:'1s'},evidence:{mode:'failures'}},redacted:false});
      if (path === 'packages/payments/resources') return json({items:[{type:'template',resourceId:'checkout',logicalId:'checkout',state:'ready'},{type:'flow',resourceId:'purchase-flow',logicalId:'purchase-flow',state:'ready'}],total:2});
      if (path === 'packages/payments/resources/tool/lookup-customer/debug-form') return json({target:{type:'tool',id:'lookup-customer'},input:{inputs:{requestId:'default'},arguments:{limit:1}}});
      if (path === 'packages/payments/resources/template/checkout/quick-load-form') return json({target:{type:'template',id:'checkout'},input:{inputs:{},vars:{}},redacted:false});
      if (/^packages\/payments\/resources\/(template|flow)\/[^/]+\/quick-load-form$/.test(path)) {const parts=path.split('/');return json({target:{type:parts[3],id:parts[4]},input:{inputs:{},vars:{}},redacted:false});}
      if (path === 'jobs') { const items=[...jobs.values()].filter(job => !url.searchParams.has('executionType') || job.command === 'load'); return json({items,total:items.length,offset:0,limit:100,groups:[],principalSuggestions:['alice'],packageSuggestions:['payments']}); }
      const match = /^jobs\/([^/]+)(?:\/(.*))?$/.exec(path);
      if (match) {
        const [, id, action] = match, job = jobs.get(id);
        if (!job) return json({ message: 'Unavailable' }, 404);
        if (action === 'replay') return json(id === ids.old ? { jobId:id,available:false,reason:'Rerun unavailable for this historical job' } : {jobId:id,available:true,definition:definitionFor(id),revisionPolicy:'reject-package-changes',revision:'a'.repeat(64)});
        if (action === 'replay-draft' && method === 'POST') {
          const body=request.postDataJSON();
          if(body.sourcePolicy==='strict'&&id===ids.run)return json({error:{code:'ATT-SERVER-REPLAY-REVISION-CONFLICT',summary:'Original package revision conflict'}},409);
          return json(makeReplayDraft(id,body),201);
        }
        if (action?.startsWith('replay-draft/')) {
          const draft=replayDrafts.get(action.split('/')[1]);
          return draft&&draft.sourceJobId===id ? json({schemaVersion:draft.schemaVersion,draftRef:draft.draftRef,sourceJobId:id,packageId:draft.packageId,executionType:draft.executionType,sourcePolicy:draft.sourcePolicy,originalRevision:draft.originalRevision,currentRevision:draft.currentRevision,definition:draft.definition,originalConfiguration:draft.originalConfiguration,configuration:draft.configuration,sourcePath:draft.sourcePath,expiresAt:draft.expiresAt,missingFields:[]}) : json({error:{code:'ATT-SERVER-REPLAY-DRAFT-EXPIRED',summary:'Replay draft expired; reopen the source Job.'}},410);
        }
        if (action === 'rerun') {
          const body=request.postDataJSON();submits.push(body);
          if(staleLoadSubmit&&id===ids.load){staleLoadSubmit=false;return json({error:{code:'ATT-SERVER-REPLAY-DRAFT-STALE',summary:'The Package changed after replay validation. Revalidate your edits.'}},409);}
          const created = { ...job, jobId: 'J-new-' + (++sequence), rerunOfJobId: id }; jobs.set(created.jobId, created);
          const accepted=[...replayDrafts.values()].find(item=>item.intentId===body.intentId);if(accepted)jobDefinitions.set(created.jobId,accepted.definition);
          return json(created, 202);
        }
        if (action === 'result') return json({ job, result: { status: 'PASS' } });
        if (action === 'events') return route.fulfill({ contentType: 'text/event-stream', body: Array.from({ length: 550 }, (_, index) => 'id: ' + (index + 1) + '\nevent: log\ndata: ' + JSON.stringify({ console: 'RETAINED_OUTPUT ' + index }) + '\n\n').join('') + 'id: 551\nevent: status\ndata: {"status":"PASS"}\n\n' });
        if (action === 'artifacts') return json({ items: [] });
        if (!action) return json(job);
      }
      if (path === 'schedules/preview') return json({ nextRunAt, nextOccurrences: [nextRunAt] });
      if (path === 'schedules' && method === 'POST') {
        const body = request.postDataJSON(); saves.push(body); const created = { ...body, id: 'S-' + saves.length, nextRunAt, inlineSnapshot: true }; schedules.push(created); return json(created, 201);
      }
      if (path === 'schedules') return json({ items: schedules });
      if (/^schedules\/[^/]+\/executions$/.test(path)) return json({ items: [{ occurrenceId: 'O1', scheduledFor: nextRunAt, triggerType: 'manual', status: 'submitted', jobId: ids.load, jobStatus: 'PASS' }] });
      if (/^schedules\/[^/]+\/trigger$/.test(path)) return json({ occurrenceId: 'O1', jobId: ids.load, status: 'submitted' }, 202);
      return route.fallback();
    });
    const page = await context.newPage(), errors = []; page.on('pageerror', error => errors.push(error.message));
    const base = fixture.origin + '/att/ui/';
    // Both entry points/actions must preserve the surrounding layout at every supported width.
    for (const width of [1440, 768, 390]) {
      await page.setViewportSize({width, height:900});
      for (const detail of [false, true]) {
        await page.goto(base + (detail ? `#/jobs/${ids.load}` : '#/jobs'));
        const container = detail ? page.locator('.att-job-detail') : page.getByRole('row').filter({has:page.getByRole('link',{name:`Unknown target · ${ids.load}`,exact:true})});
        for (const action of ['Rerun with same settings', 'Schedule / Reschedule…']) {
          const opener=container.getByRole('button',{name:action,exact:true});
          await opener.waitFor(); const before=await container.boundingBox();
          await opener.click();
          const modal=page.locator('dialog.att-replay-review');
          await modal.getByRole('button',{name:action==='Rerun with same settings'?'Confirm rerun':'Continue to New Schedule',exact:true}).and(page.locator(':enabled')).waitFor();
          assert.equal(await page.locator('body > dialog.att-replay-review').count(),1);
          const after=await container.boundingBox(); assert.equal(after.height,before.height,'Opening review must not change row/Details geometry');
          assert.equal(await modal.locator('details').getAttribute('open'),null);
          const box=await modal.boundingBox(); assert.ok(box.x>=0&&box.x+box.width<=width+1&&box.height<=900*.85+1,JSON.stringify({width,box}));
          await modal.getByRole('button',{name:'Cancel',exact:true}).focus(); await page.keyboard.press('Tab');
          assert.ok(await modal.evaluate(element=>element.contains(document.activeElement)),'Tab stays inside modal');
          await page.keyboard.press('Shift+Tab'); assert.equal(await page.evaluate(()=>document.activeElement.textContent),'Cancel');
          await modal.locator('summary').click(); assert.equal(await modal.locator('details').getAttribute('open'),'');
          assert.ok(await modal.locator('pre').evaluate(element=>element.clientHeight<=innerHeight*.32+1));
          await page.addScriptTag({path:require.resolve('axe-core/axe.min.js')});
          const violations=await page.evaluate(async()=> (await axe.run(document.querySelector('dialog.att-replay-review'),{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}})).violations);assert.deepEqual(violations,[]);
          if(process.env.ATT_UI_SCREENSHOT_DIR)await page.screenshot({path:`${process.env.ATT_UI_SCREENSHOT_DIR}/replay-${width}-${detail?'detail':'list'}-${action==='Rerun with same settings'?'run':'schedule'}.png`});
          await page.keyboard.press('Escape'); await modal.waitFor({state:'hidden'}); assert.ok(await opener.evaluate(element=>element===document.activeElement),'Restore focus to opener');
        }
      }
    }
    await page.setViewportSize({width:1280,height:900});
    await page.goto(base+'#/jobs');
    const runRow=page.getByRole('row').filter({has:page.getByRole('link',{name:'Unknown target · '+ids.run,exact:true})});
    await runRow.getByRole('button',{name:'Rerun with same settings',exact:true}).click();
    const strictReview=page.getByRole('dialog',{name:'Review rerun '+ids.run});
    await strictReview.getByRole('radio',{name:'Require original revision (strict)'}).click();
    await strictReview.getByRole('button',{name:'Review selected revision'}).click();
    await strictReview.getByText('Original package revision conflict',{exact:false}).waitFor();
    await strictReview.getByRole('button',{name:'Review with current Package'}).click();
    await strictReview.getByRole('button',{name:'Confirm rerun'}).and(page.locator(':enabled')).waitFor();
    await strictReview.getByRole('button',{name:'Cancel',exact:true}).click();

    await runRow.getByRole('button',{name:'Configure & Rerun',exact:true}).click();
    await page.waitForURL(url=>url.hash.startsWith('#/run/payments?'));
    const runUrl=new URL(page.url());assert.match(runUrl.hash,/replayDraft=R[0-9A-F]{32}/);assert.match(runUrl.hash,/replaySourceJob=J[0-9A-F]{16}/);assert.ok(!runUrl.hash.includes('scenario'));
    await page.getByLabel('Target ID').waitFor();assert.equal(await page.getByLabel('Target ID').inputValue(),'lookup-customer');
    assert.equal(await page.getByLabel('Debug diagnostics').isChecked(),true);assert.equal(await page.getByLabel('Stop after the first failed Case').isChecked(),true);
    assert.equal(await page.locator('input[aria-label="limit value"]').inputValue(),'3');
    await page.getByRole('button',{name:'Validate',exact:true}).click();
    await page.getByRole('region',{name:'Review RUN'}).waitFor();
    assert.deepEqual(replayDraftRequests.at(-1).body.overrides,{environment:'SIT',options:{debug:true,failFast:true},target:{type:'tool',id:'lookup-customer'},input:{inputs:{requestId:'R1'},arguments:{limit:3}}});

    await page.goto(base+'#/jobs');
    const loadRow=page.getByRole('row').filter({has:page.getByRole('link',{name:'Unknown target · '+ids.load,exact:true})});
    await loadRow.getByRole('button',{name:'Configure & Rerun',exact:true}).click();
    await page.waitForURL(url=>url.hash.startsWith('#/advanced-load/payments?'));
    await page.getByLabel('Measured duration value').waitFor();
    assert.equal(await page.getByLabel('Measured duration value').inputValue(),'30');
    assert.equal(await page.getByLabel('Measured duration unit').inputValue(),'m');
    assert.equal(await page.getByLabel('Load model').isDisabled(),true);
    const workloadIds=page.locator('label').filter({hasText:'Workload ID'}).locator('input');
    assert.equal(await workloadIds.count(),2);assert.equal(await workloadIds.nth(0).inputValue(),'checkout-main');assert.equal(await workloadIds.nth(1).inputValue(),'checkout-mix');
    await page.getByLabel('Measured duration value').fill('24');await page.getByLabel('Measured duration unit').selectOption('h');
    await page.getByRole('button',{name:'Validate scenario'}).click();
    await page.getByRole('region',{name:'Review LOAD replay changes'}).waitFor();
    const editedRequest=replayDraftRequests.at(-1).body;
    assert.equal(editedRequest.overrides.load.duration,'24h');assert.deepEqual(editedRequest.overrides.workloads.map(item=>item.id),['checkout-main','checkout-mix']);
    assert.ok(await page.getByText('Configured target arrival rate is the pacing request',{exact:false}).count());
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Run new LOAD Job'}).click();
    await page.getByText('Package changed after replay validation',{exact:false}).waitFor();
    assert.equal(await page.getByLabel('Measured duration value').inputValue(),'24');assert.equal(await page.getByLabel('Measured duration unit').inputValue(),'h');
    await page.getByRole('button',{name:'Validate scenario'}).click();
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Run new LOAD Job'}).click();
    await page.waitForURL(url=>url.hash.includes('/jobs/J-new-'));

    for (const type of ['run', 'load']) {
      await page.goto(base + '#/jobs');
      const sourceId=ids[type],row = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Unknown target · '+sourceId, exact: true }) });
      await row.getByRole('button', { name: 'Rerun with same settings', exact: true }).click();
      const review = page.getByRole('dialog', { name: 'Review rerun '+sourceId });
      await review.getByRole('button', { name: 'Confirm rerun', exact: true }).waitFor({ state: 'visible' });
      assert.equal(submits.length, type === 'run' ? 0 : 1);
      await review.getByRole('button', { name: 'Confirm rerun', exact: true }).click();
      await page.waitForURL(url => url.hash.includes('/jobs/J-new-'));
      await page.getByRole('heading', { name: /Job J-new-/ }).waitFor();
      await page.getByRole('button', { name: 'Schedule / Reschedule…', exact: true }).click();
      await page.getByRole('button', { name: 'Continue to New Schedule', exact: true }).click();
      await page.waitForURL(url => url.hash.startsWith('#/schedules/new'));
      await page.getByRole('heading', { name: 'New Schedule', exact: true }).waitFor();
      await page.locator('#schedule-name').fill(type + ' from job');
      await page.getByText(/^Next run:/).waitFor();
      await page.getByRole('button', { name: 'Save schedule', exact: true }).click();
      await page.waitForURL(url => url.hash === '#/schedules');
      assert.equal(saves.at(-1).executionType, type); assert.ok(saves.at(-1).draftId);
      await page.getByRole('button', { name: 'Run Now ' + type + ' from job', exact: true }).first().click();
      await page.getByRole('button', { name: 'Confirm Run Now', exact: true }).click();
      await page.waitForURL(url => /#\/schedules\/S-\d+\/history/.test(url.hash));
      await page.getByRole('button', { name: ids.load, exact: true }).click();
      await page.waitForURL(url => url.hash === '#/jobs/'+ids.load);
    }
    await page.goto(base + '#/jobs?type=load');
    const history = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Unknown target · '+ids.load, exact: true }) });
    await history.getByRole('button', { name: 'Rerun with same settings', exact: true }).click();
    await page.getByRole('dialog', { name: 'Review rerun '+ids.load }).getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.goto(base + '#/jobs/'+ids.old);
    await page.getByRole('button', { name: 'Rerun with same settings', exact: true }).click();
    await page.getByText('Rerun unavailable for this historical job', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Confirm rerun', exact: true }).isDisabled(), true);
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + '#/jobs/'+ids.load);
    await page.getByRole('button', { name: 'Rerun with same settings', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm rerun', exact: true }).and(page.locator(':enabled')).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Review must fit a phone viewport');
    await page.evaluate(() => { location.hash = '#/jobs'; });
    await page.getByRole('dialog', { name: 'Discard your changes?' }).waitFor();
    await page.getByRole('button', { name: 'Stay on this page', exact: true }).click();
    await page.getByRole('dialog', { name: 'Discard your changes?' }).waitFor({ state: 'hidden' });
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('dialog', { name: 'Review rerun '+ids.load }).count(), 0);
    // Same mounted route: history -> list -> edit, then cancel/back/forward.
    await page.goto(base + '#/schedules/S-1/history');
    await page.getByRole('heading', { name: /Execution history/ }).waitFor();
    await page.getByRole('button', { name: 'Back to Schedules', exact: true }).click();
    await page.getByRole('button', { name: 'Edit run from job', exact: true }).click();
    await page.locator('#schedule-name').waitFor();
    assert.equal(await page.locator('#schedule-name').inputValue(), 'run from job');
    await page.locator('#schedule-name').fill('Dirty name');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await page.waitForURL(url => url.hash === '#/schedules');
    await page.locator('#schedule-list-heading').waitFor();
    await page.goBack();
    await page.locator('#schedule-name').waitFor();
    assert.equal(await page.locator('#schedule-name').inputValue(), 'run from job');
    await page.goForward();
    await page.waitForURL(url => url.hash === '#/schedules');
    // A 640x450 CSS viewport also exercises the effective layout of a 1280x900 window at 200% zoom.
    // Standalone pages load only the URL-bound Job, and fill the available viewport.
    for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }, { width: 768, height: 600 }, { width: 640, height: 450 }]) {
      await page.setViewportSize(viewport);
      const before = broadRequests.length;
      await page.goto(base + '#/jobs/'+ids.load+'/console?view=live');
      await page.locator('.att-standalone-console pre.att-console').waitFor();
      await page.getByText('RETAINED_OUTPUT 549', { exact: false }).waitFor();
      assert.equal(broadRequests.length, before, 'Standalone must not load broad jobs or packages');
      assert.equal(await page.locator('aside, .app-header, .att-breadcrumbs, footer, .att-floating-console').count(), 0);
      assert.equal(await page.getByRole('tablist', {name:'Job '+ids.load+' sections'}).getByRole('tab').count(), 6);

      for (const view of ['events', 'live']) {
        await page.getByRole('tab', { name: view === 'live' ? 'Live Output' : 'Events', exact: true }).click();
        await page.waitForURL(url => url.hash === '#/jobs/'+ids.load+'?standalone=1&tab=' + view);
        const surface = page.locator(view === 'live' ? 'pre.att-console' : 'ol.att-job-events');
        await surface.waitFor();
        const box = await surface.boundingBox();
        assert.ok(box.height > 70, JSON.stringify({ viewport, view, box }));
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
      }
      await page.reload();
      await page.locator('.att-standalone-console pre.att-console').waitFor();
    }
    if (process.env.ATT_UI_SCREENSHOT) await page.screenshot({ path: process.env.ATT_UI_SCREENSHOT });
    await page.goto(base + '#/jobs/missing/console');
    await page.getByRole('heading', { name: 'Job Details unavailable' }).waitFor();
    assert.equal(await page.locator('aside, nav, .app-header, footer').count(), 0);
    assert.deepEqual(errors, []); await context.close();
    console.log('4.2.5 acceptance passed: RUN/LOAD replay and Schedule, History/List/Edit, standalone fixed-Job navigation, virtualized Events, viewport fill, mobile, reload and safe missing-Job state.');
  } finally { await browser.close(); await stopFixture(fixture); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
