const assert = require('node:assert/strict');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

async function main() {
  const fixture = await startFixture(); const browser = await launchBrowser();
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    context.setDefaultTimeout(15000);
    await selectFixture(context,fixture.origin,'job-replay');
    const fixtureApi=context.request;
    const packageDefaultProjectionResponse=await fixtureApi.get(fixture.origin+'/att/api/v1/packages/payments/package-configuration/effective');
    const packageDefaultProjection=await packageDefaultProjectionResponse.json();assert.equal(packageDefaultProjectionResponse.status(),200);
    assert.equal(packageDefaultProjection.environment,'SIT');assert.equal(packageDefaultProjection.environmentOrigin,'package');
    const environmentProjectionResponse=await fixtureApi.get(fixture.origin+'/att/api/v1/packages/payments/package-configuration/effective?environment=SIT');
    const environmentProjection=await environmentProjectionResponse.json();assert.equal(environmentProjectionResponse.status(),200);
    assert.equal(environmentProjection.environment,'SIT');assert.equal(environmentProjection.environmentOrigin,'request');
    const fixtureJobsResponse=await fixtureApi.get(fixture.origin+'/att/api/v1/jobs');
    assert.equal(fixtureJobsResponse.status(),200,'The context request carries the selected fixture cookie');
    const fixtureJobs=await fixtureJobsResponse.json();
    assert.ok(fixtureJobs.items.some(job=>job.jobId==='J0000000000000002'));
    const fixtureDraft=await fixtureApi.post(fixture.origin+'/att/api/v1/jobs/J0000000000000002/replay-draft',{data:{sourcePolicy:'current',overrides:{load:{duration:'24h'}}}});
    const fixtureDraftBody=await fixtureDraft.json();assert.equal(fixtureDraft.status(),201);assert.equal(fixtureDraftBody.schemaVersion,'att-job-replay-draft/v1');
    assert.equal(fixtureDraftBody.available,true,'A valid current-package replay draft remains runnable');assert.equal(fixtureDraftBody.jobId,'J0000000000000002');assert.ok(fixtureDraftBody.intentId);
    const fixturePrefillResponse=await fixtureApi.get(fixture.origin+`/att/api/v1/jobs/J0000000000000002/replay-draft/${fixtureDraftBody.draftRef}`);
    const fixturePrefill=await fixturePrefillResponse.json();assert.equal(fixturePrefillResponse.status(),200);
    assert.equal(fixturePrefill.definition.scenario.load.duration,'24h');assert.equal(fixturePrefill.definition.scenario.workloads.length,2);
    const staleFixture=await fixtureApi.post(fixture.origin+'/att/api/v1/jobs/J0000000000000002/rerun',{data:{confirm:true,intentId:fixtureDraftBody.intentId,sourcePolicy:'current'}});
    assert.equal(staleFixture.status(),409);assert.equal((await staleFixture.json()).error.code,'ATT-SERVER-REPLAY-DRAFT-STALE');
    const readyFixtureDraftResponse=await fixtureApi.post(fixture.origin+'/att/api/v1/jobs/J0000000000000002/replay-draft',{data:{sourcePolicy:'current'}});
    const readyFixtureDraft=await readyFixtureDraftResponse.json();
    const fixtureAccepted=await fixtureApi.post(fixture.origin+'/att/api/v1/jobs/J0000000000000002/rerun',{data:{confirm:true,intentId:readyFixtureDraft.intentId,sourcePolicy:'current'}}).then(response=>response.json());
    const fixtureRetry=await fixtureApi.post(fixture.origin+'/att/api/v1/jobs/J0000000000000002/rerun',{data:{confirm:true,intentId:readyFixtureDraft.intentId,sourcePolicy:'current'}}).then(response=>response.json());
    assert.equal(fixtureRetry.jobId,fixtureAccepted.jobId,'Same confirmation intent returns the accepted Job.');
    const ids = { run:'J0000000000000001', load:'J0000000000000002', old:'J0000000000000003', schedule:'J0000000000000004', arrival:'J0000000000000005' };
    const jobs = new Map(['run', 'load', 'schedule', 'arrival'].map(type => [ids[type], { jobId: ids[type], command: type==='schedule'||type==='arrival'?'load':type, executionType: type==='schedule'||type==='arrival'?'load':type, packageId: 'payments', principal: 'alice', status: 'PASS' }]));
    jobs.set(ids.old, { jobId: ids.old, command: 'load', packageId: 'payments', status: 'PASS' });
    const jobDefinitions = new Map();
    const submits = [], saves = [], broadRequests = [], replayDraftRequests = []; let schedules = []; let sequence = 0, draftSequence=0, staleLoadSubmit=false, staleLoadUsed=false;
    const replayDrafts = new Map();
    const originalDefinition = id => {
      if(id===ids.run)return {executionType:'run',packageId:'payments',environment:'SIT',schemaVersion:'att-execution/v1',target:{type:'tool',id:'lookup-customer'},input:{inputs:{requestId:'R1'},arguments:{limit:3}},options:{debug:true,failFast:true}};
      if(id===ids.arrival)return {executionType:'load',packageId:'payments',environment:'SIT',sourcePath:'load/arrival.yaml',scenario:{schemaVersion:'att-load/v1.9',load:{warmup:'2s',rampUp:'3s',duration:'30m',rampDown:'4s'},workloads:[
        {id:'checkout-arrival',target:{type:'template',id:'checkout'},load:{arrivalRate:'5/s',maxConcurrent:8,overloadPolicy:'drop'},thresholds:{p95:'< 800ms'}}
      ],thresholds:{minThroughput:'>= 10/s'},evidence:{mode:'failures',maxFailureSamples:5}}};
      return {executionType:'load',packageId:'payments',environment:'SIT',sourcePath:'load/nightly.yaml',scenario:{schemaVersion:'att-load/v1.9',load:{warmup:'2s',rampUp:'3s',duration:'30m',rampDown:'4s'},workloads:[
        {id:'checkout-main',target:{type:'template',id:'checkout'},inputs:{region:'HK'},load:{users:4},thresholds:{p95:'< 800ms'}},
        {id:'checkout-mix',mix:[{id:'purchase',weight:100,target:{type:'flow',id:'purchase-flow'}}],load:{users:2}},
      ],testdata:['examples/accounts.yaml'],thresholds:{minThroughput:'>= 10/s'},evidence:{mode:'failures',maxFailureSamples:5}}};
    };
    const definitionFor = id => jobDefinitions.get(id) || originalDefinition(id);
    const makeReplayDraft = (id, body) => {
      const definition=JSON.parse(JSON.stringify(definitionFor(id))),originalDefinition=JSON.parse(JSON.stringify(definition)),overrides=body.overrides||{};
      if(definition.scenario){
        if(overrides.environment)definition.environment=overrides.environment;else if(overrides.environment==='')definition.environment='SIT';
        Object.assign(definition.scenario.load||{},overrides.load||{});
        for(const patch of overrides.workloads||[]){const row=definition.scenario.workloads.find(item=>item.id===patch.id);if(row){Object.assign(row.load,patch.load||{});for(const field of ['execution','target','mix','inputs','vars','testdata','thresholds','evidence'])if(patch[field]!==undefined)row[field]=patch[field];}}
        for(const field of ['execution','testdata','thresholds','evidence','seed'])if(overrides[field]!==undefined)definition.scenario[field]=overrides[field];
      }else{
        if(overrides.environment)definition.environment=overrides.environment;else if(overrides.environment==='')definition.environment='SIT';
        Object.assign(definition.options,overrides.options||{});if(overrides.target)definition.target=overrides.target;if(overrides.selection)definition.selection=overrides.selection;if(overrides.input)definition.input=overrides.input;
      }
      const prefix=definition.executionType==='run'?'R':'C',code=(++draftSequence).toString(16).toUpperCase().padStart(32,'0');
      const draftRef=prefix+code,draftId=draftRef,intentId=`00000000-0000-4000-8000-${draftSequence.toString(16).padStart(12,'0')}`;
      const expiresAt='2099-01-01T00:00:00Z';
      const originalRevision=id===ids.schedule?'c'.repeat(64):id.startsWith('J-new-')?'b'.repeat(64):'a'.repeat(64),currentRevision=id===ids.schedule?'c'.repeat(64):'b'.repeat(64);
      const item={schemaVersion:'att-job-replay-draft/v1',draftRef,draftId,intentId,jobId:id,available:true,sourceJobId:id,packageId:'payments',executionType:definition.executionType,
        sourcePolicy:body.sourcePolicy||'strict',originalRevision,currentRevision,revision:currentRevision,originalDefinition,definition,
        originalConfiguration:{packageId:'payments',environment:'SIT',environmentOrigin:'package',origins:{environment:'package'},authoredPaths:{environment:'package.yaml'},packageRevision:originalRevision},
        configuration:{packageId:'payments',environment:definition.environment||'dev',environmentOrigin:'package',origins:{environment:'package'},authoredPaths:{environment:'package.yaml'},packageRevision:currentRevision},sourcePath:definition.sourcePath,expiresAt,
        preview:{draftId,packageId:'payments',model:definition.scenario?.workloads?.[0]?.load?.arrivalRate?'arrivalRate':'virtualUsers',previewYaml:JSON.stringify(definition.scenario||definition,null,2),expiresAt,redacted:false,diagnostics:[]}};
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
      if (path === 'packages/payments/package-configuration/effective') return json({packageId:'payments',present:true,canEdit:false,manifestDigest:'b'.repeat(64),environment:url.searchParams.get('environment')||'SIT',environmentOrigin:url.searchParams.has('environment')?'request':'package'});
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
        if (action === 'replay') {
          if(id===ids.old)return json({jobId:id,available:false,reason:'Rerun unavailable for this historical job'});
          const revision=id===ids.schedule?'c'.repeat(64):id.startsWith('J-new-')?'b'.repeat(64):'a'.repeat(64);
          const definition=definitionFor(id);
          return json({jobId:id,available:true,definition,revisionPolicy:'reject-package-changes',revision,
            configuration:{packageId:'payments',environment:'SIT',environmentOrigin:'package',origins:{environment:'package'},authoredPaths:{environment:'package.yaml'},packageRevision:revision},sourcePath:definition.sourcePath});
        }
        if (action === 'replay-draft' && method === 'POST') {
          const body=request.postDataJSON();
          if(body.sourcePolicy==='strict'&&id===ids.run)return json({error:{code:'ATT-SERVER-REPLAY-REVISION-CONFLICT',summary:'Original package revision conflict'}},409);
          return json(makeReplayDraft(id,body),201);
        }
        if (action?.startsWith('replay-draft/')) {
          const draft=replayDrafts.get(action.split('/')[1]);
          return draft&&draft.sourceJobId===id ? json({schemaVersion:draft.schemaVersion,draftRef:draft.draftRef,sourceJobId:id,packageId:draft.packageId,executionType:draft.executionType,sourcePolicy:draft.sourcePolicy,originalRevision:draft.originalRevision,currentRevision:draft.currentRevision,originalDefinition:draft.originalDefinition,definition:draft.definition,originalConfiguration:draft.originalConfiguration,configuration:draft.configuration,sourcePath:draft.sourcePath,expiresAt:draft.expiresAt,missingFields:[],validationState:'valid'}) : json({error:{code:'ATT-SERVER-REPLAY-DRAFT-EXPIRED',summary:'Replay draft expired; reopen the source Job.'}},410);
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
      const scheduleMatch=/^schedules\/([^/]+)\/(executions|trigger)$/.exec(path);
      if (scheduleMatch) {
        const schedule=schedules.find(item=>item.id===scheduleMatch[1]);
        const scheduledJobId=schedule?.executionType==='run'?ids.run:ids.load;
        if(scheduleMatch[2]==='executions')return json({items:[{occurrenceId:'O1',scheduledFor:nextRunAt,triggerType:'manual',status:'submitted',jobId:scheduledJobId,jobStatus:'PASS'}]});
        return json({occurrenceId:'O1',jobId:scheduledJobId,status:'submitted'},202);
      }
      return route.fallback();
    });
    const page = await context.newPage(), errors = []; page.on('pageerror', error => errors.push(error.message));
    const base = fixture.origin + '/att/ui/';
    async function leaveDirtyEditorFor(hash) {
      await page.goto(base+hash);
      const dialog=page.getByRole('dialog',{name:'Discard your changes?'});
      await dialog.waitFor();
      await dialog.getByRole('button',{name:'Discard changes and continue',exact:true}).click();
      await page.waitForURL(url=>url.hash===hash);
    }
    // Both entry points/actions must preserve the surrounding layout at every supported width.
    for (const width of [1440, 768, 390]) {
      await page.setViewportSize({width, height:900});
      for (const detail of [false, true]) {
        for (const action of ['Rerun', 'Schedule / Reschedule…']) {
          const layoutJobId=action==='Rerun'?ids.load:ids.schedule;
          await page.goto(base + (detail ? `#/jobs/${layoutJobId}` : '#/jobs'));
          const container = detail ? page.locator('.att-job-detail') : page.getByRole('row').filter({has:page.getByRole('link',{name:`Unknown target · ${layoutJobId}`,exact:true})});
          const opener=container.getByRole('button',{name:action,exact:true});
          await opener.waitFor(); const before=await container.boundingBox();
          await opener.click();
          const modal=page.locator('dialog.att-replay-review');
          await modal.getByRole('button',{name:action==='Rerun'?'Rerun with same settings':'Continue to New Schedule',exact:true}).and(page.locator(':enabled')).waitFor();
          assert.equal(await page.locator('body > dialog.att-replay-review').count(),1);
          const after=await container.boundingBox(); assert.equal(after.height,before.height,'Opening review must not change row/Details geometry');
          const beforeAfterDetails=modal.locator('details.att-replay-advanced').filter({has:page.getByText('View sanitized before and after definitions',{exact:true})});
          const rawDefinitionDetails=modal.locator('details.att-replay-advanced').filter({has:page.getByText('Advanced / View raw definition',{exact:true})});
          assert.equal(await rawDefinitionDetails.count(),1,'The review exposes one sanitized raw definition disclosure');
          assert.equal(await rawDefinitionDetails.getAttribute('open'),null);
          if(action==='Rerun')assert.equal(await beforeAfterDetails.count(),1,'Rerun review exposes the before/after disclosure');
          else assert.equal(await beforeAfterDetails.count(),0,'Schedule review does not render the Rerun-only before/after disclosure');
          if(await beforeAfterDetails.count())assert.equal(await beforeAfterDetails.getAttribute('open'),null);
          const box=await modal.boundingBox(); assert.ok(box.x>=0&&box.x+box.width<=width+1&&box.height<=900*.85+1,JSON.stringify({width,box}));
          await modal.getByRole('button',{name:'Cancel',exact:true}).focus(); await page.keyboard.press('Tab');
          assert.ok(await modal.evaluate(element=>element.contains(document.activeElement)),'Tab stays inside modal');
          await page.keyboard.press('Shift+Tab'); assert.equal(await page.evaluate(()=>document.activeElement.textContent),'Cancel');
          await rawDefinitionDetails.getByText('Advanced / View raw definition',{exact:true}).click();
          assert.equal(await rawDefinitionDetails.getAttribute('open'),'');
          assert.ok(await rawDefinitionDetails.locator('pre[aria-label="Sanitized execution definition"]').evaluate(element=>element.clientHeight<=innerHeight*.32+1));
          await page.addScriptTag({path:require.resolve('axe-core/axe.min.js')});
          const violations=await page.evaluate(async()=> (await axe.run(document.querySelector('dialog.att-replay-review'),{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}})).violations);assert.deepEqual(violations,[]);
          if(process.env.ATT_UI_SCREENSHOT_DIR)await page.screenshot({path:`${process.env.ATT_UI_SCREENSHOT_DIR}/replay-${width}-${detail?'detail':'list'}-${action==='Rerun'?'run':'schedule'}.png`});
          await page.keyboard.press('Escape'); await modal.waitFor({state:'hidden'}); assert.ok(await opener.evaluate(element=>element===document.activeElement),'Restore focus to opener');
        }
      }
    }
    await page.setViewportSize({width:1280,height:900});
    await page.goto(base+'#/jobs');
    const runRow=page.getByRole('row').filter({has:page.getByRole('link',{name:'Unknown target · '+ids.run,exact:true})});
    await runRow.getByRole('button',{name:'Rerun',exact:true}).click();
    const strictReview=page.getByRole('dialog',{name:'Review rerun '+ids.run});
    await strictReview.getByRole('radio',{name:'Require original revision (strict)'}).click();
    await strictReview.getByRole('button',{name:'Review selected revision'}).click();
    await strictReview.getByText('Original package revision conflict',{exact:false}).waitFor();
    await strictReview.getByRole('button',{name:'Review with current Package'}).click();
    await strictReview.getByRole('button',{name:'Rerun with same settings'}).and(page.locator(':enabled')).waitFor();
    await strictReview.getByRole('button',{name:'Configure & Rerun',exact:true}).click();
    await page.waitForURL(url=>url.hash.startsWith('#/run/payments?'));
    await page.getByText('Job settings loaded. Review the retained fields',{exact:false}).waitFor();
    const runUrl=new URL(page.url());assert.match(runUrl.hash,/replayDraft=R[0-9A-F]{32}/);assert.match(runUrl.hash,/replaySourceJob=J[0-9A-F]{16}/);assert.ok(!runUrl.hash.includes('scenario'));
    assert.equal(new URLSearchParams(runUrl.hash.split('?')[1]||'').has('environment'),false,'Package-default replay does not turn the resolved environment into an explicit URL selection');
    const runStorage=await page.evaluate(()=>JSON.stringify({local:Object.keys(localStorage).map(key=>[key,localStorage.getItem(key)]),session:Object.keys(sessionStorage).map(key=>[key,sessionStorage.getItem(key)])}));
    for(const forbidden of ['requestId','arguments','scenario','password'])assert.ok(!runStorage.includes(forbidden),`Raw replay definition leaked to storage: ${forbidden}`);
    await page.getByLabel('Target ID').waitFor();assert.equal(await page.getByLabel('Target ID').inputValue(),'lookup-customer');
    assert.equal(await page.getByLabel('Environment').inputValue(),'');
    await page.getByText('Resolved Environment: SIT',{exact:false}).waitFor();await page.getByText('Origin: Package Manifest',{exact:false}).waitFor();
    assert.equal(await page.getByLabel('Debug diagnostics').isChecked(),true);assert.equal(await page.getByLabel('Stop after the first failed Case').isChecked(),true);
    assert.equal(await page.locator('input[aria-label="limit value"]').inputValue(),'3');
    await page.reload();await page.getByLabel('Target ID').waitFor();assert.equal(await page.getByLabel('Target ID').inputValue(),'lookup-customer','Refresh restores the server-side draft');
    await page.getByLabel('Target ID').fill('edited-before-back');await page.goBack();
    await page.getByRole('dialog',{name:'Discard your changes?'}).waitFor();
    await page.getByRole('button',{name:'Stay on this page'}).click();
    assert.equal(await page.getByLabel('Target ID').inputValue(),'edited-before-back','Back keeps the user in the dirty editor');
    await page.getByLabel('Target ID').fill('lookup-customer');
    await page.getByText(/^Resolved Environment: SIT/).waitFor();
    await page.getByText("Typed fields use the selected target's safe Debug defaults.",{exact:false}).waitFor();
    assert.equal(await page.locator('input[aria-label="requestId value"]').inputValue(),'R1','A target-default refresh must keep the retained request input');
    assert.equal(await page.locator('input[aria-label="limit value"]').inputValue(),'3','A target-default refresh must keep the retained typed argument');
    await page.getByRole('button',{name:'Validate',exact:true}).click();
    await page.getByRole('region',{name:'Review RUN'}).waitFor();
    assert.deepEqual(replayDraftRequests.at(-1).body.overrides,{environment:'',options:{debug:true,failFast:true},target:{type:'tool',id:'lookup-customer'},input:{inputs:{requestId:'R1'},arguments:{limit:3}}});

    await leaveDirtyEditorFor('#/jobs');
    const loadRow=page.getByRole('row').filter({has:page.getByRole('link',{name:'Unknown target · '+ids.load,exact:true})});
    await loadRow.getByRole('button',{name:'Rerun',exact:true}).click();
    await page.getByRole('dialog',{name:'Review rerun '+ids.load}).getByRole('button',{name:'Configure & Rerun',exact:true}).click();
    await page.waitForURL(url=>url.hash.startsWith('#/advanced-load/payments?'));
    await page.getByText('Job settings loaded. Edit the fields',{exact:false}).waitFor();
    assert.equal(new URLSearchParams(new URL(page.url()).hash.split('?')[1]||'').has('environment'),false,'Package-default LOAD replay leaves the environment to current Package resolution');
    assert.equal(await page.getByLabel('Environment').inputValue(),'');
    await page.getByText('Resolved Environment: SIT',{exact:false}).waitFor();await page.getByText('Origin: Package Manifest',{exact:false}).waitFor();
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
    const appJobCountBeforeStale=jobs.size;
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Run new LOAD Job'}).click();
    const staleAlert=page.getByRole('alert').filter({hasText:'Package changed after replay validation'});
    await staleAlert.waitFor();assert.equal(await page.getByRole('alert').count(),1,'Stale replay is announced once in a single alert');
    assert.equal(await staleAlert.count(),1);
    assert.equal(await page.getByLabel('Measured duration value').inputValue(),'24');assert.equal(await page.getByLabel('Measured duration unit').inputValue(),'h');
    assert.equal(jobs.size,appJobCountBeforeStale,'A stale-draft 409 does not create a Job');
    await page.getByRole('button',{name:'Validate again',exact:true}).click();
    await page.getByRole('region',{name:'Review LOAD replay changes'}).waitFor();
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Run new LOAD Job'}).click();
    await page.waitForURL(url=>url.hash.includes('/jobs/J-new-'));
    assert.equal(await page.getByRole('dialog',{name:'Discard your changes?'}).count(),0,'Successful replay releases the editor dirty guard before navigating');
    assert.equal(jobs.size,appJobCountBeforeStale+1,'The revalidated retry creates exactly one new Job');

    // Arrival Rate edits retain the model and update pacing plus concurrency without a VU conversion.
    await page.goto(base+'#/jobs');
    const arrivalRow=page.getByRole('row').filter({has:page.getByRole('link',{name:'Unknown target · '+ids.arrival,exact:true})});
    await arrivalRow.getByRole('button',{name:'Rerun',exact:true}).click();
    await page.getByRole('dialog',{name:'Review rerun '+ids.arrival}).getByRole('button',{name:'Configure & Rerun',exact:true}).click();
    await page.waitForURL(url=>url.hash.startsWith('#/advanced-load/payments?'));
    await page.getByText('Job settings loaded. Edit the fields',{exact:false}).waitFor();
    assert.equal(await page.getByLabel('Load model').inputValue(),'arrivalRate');assert.equal(await page.getByLabel('Load model').isDisabled(),true);
    assert.equal(await page.getByLabel('Arrival rate value').inputValue(),'5');assert.equal(await page.getByLabel('Maximum concurrent').inputValue(),'8');
    await page.getByLabel('Arrival rate value').fill('7');await page.getByLabel('Maximum concurrent').fill('12');
    await page.getByRole('button',{name:'Validate scenario'}).click();await page.getByRole('region',{name:'Review LOAD replay changes'}).waitFor();
    const arrivalOverrides=replayDraftRequests.at(-1).body.overrides.workloads[0];
    assert.equal(arrivalOverrides.id,'checkout-arrival');assert.deepEqual(arrivalOverrides.load,{arrivalRate:'7/s',maxConcurrent:12,overloadPolicy:'drop'});
    assert.equal(Object.prototype.hasOwnProperty.call(arrivalOverrides.load,'users'),false,'Arrival Rate replay never converts its pacing model to VUs');
    assert.ok(await page.getByText('Configured target arrival rate is the pacing request',{exact:false}).count());
    await leaveDirtyEditorFor('#/jobs');

    // The Job Details entrypoint opens the same authorized draft; refresh reloads by opaque reference.
    await page.goto(base+'#/jobs/'+ids.run);
    await page.getByRole('button',{name:'Rerun',exact:true}).click();
    await page.getByRole('dialog',{name:'Review rerun '+ids.run}).getByRole('button',{name:'Configure & Rerun',exact:true}).click();
    await page.waitForURL(url=>url.hash.startsWith('#/run/payments?'));
    await page.getByText('Job settings loaded. Review the retained fields',{exact:false}).waitFor();
    await page.getByLabel('Target ID').waitFor();assert.equal(await page.getByLabel('Target ID').inputValue(),'lookup-customer');
    await page.reload();await page.getByLabel('Target ID').waitFor();assert.equal(await page.getByLabel('Target ID').inputValue(),'lookup-customer');
    await page.getByLabel('Target ID').fill('details-edited');await page.goBack();
    await page.getByRole('dialog',{name:'Discard your changes?'}).waitFor();
    await page.getByRole('button',{name:'Stay on this page'}).click();
    assert.equal(await page.getByLabel('Target ID').inputValue(),'details-edited');

    await leaveDirtyEditorFor('#/jobs');
    for (const type of ['run', 'load']) {
      if (type !== 'run') await page.goto(base + '#/jobs');
      const sourceId=ids[type],row = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Unknown target · '+sourceId, exact: true }) });
      const submitsBeforeReview=submits.length;
      await row.getByRole('button', { name: 'Rerun', exact: true }).click();
      const review = page.getByRole('dialog', { name: 'Review rerun '+sourceId });
      await review.getByRole('button', { name: 'Rerun with same settings', exact: true }).waitFor({ state: 'visible' });
      assert.equal(submits.length-submitsBeforeReview,0,'Opening the review does not submit a Job');
      await review.getByRole('button', { name: 'Rerun with same settings', exact: true }).click();
      await page.waitForURL(url => url.hash.includes('/jobs/J-new-'));
      assert.equal(submits.length-submitsBeforeReview,1,'Confirming the review submits exactly once');
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
      const scheduledJobId=type==='run'?ids.run:ids.load;
      await page.getByRole('button', { name: scheduledJobId, exact: true }).click();
      await page.waitForURL(url => url.hash === '#/jobs/'+scheduledJobId);
    }
    await page.goto(base + '#/jobs?type=load');
    const history = page.getByRole('row').filter({ has: page.getByRole('link', { name: 'Unknown target · '+ids.load, exact: true }) });
    await history.getByRole('button', { name: 'Rerun', exact: true }).click();
    await page.getByRole('dialog', { name: 'Review rerun '+ids.load }).getByRole('button', { name: 'Configure & Rerun', exact: true }).click();
    await page.waitForURL(url=>url.hash.startsWith('#/advanced-load/payments?'));
    await page.getByText('Job settings loaded. Edit the fields',{exact:false}).waitFor();
    assert.equal(new URLSearchParams(new URL(page.url()).hash.split('?')[1]||'').has('environment'),false);
    assert.equal(await page.getByLabel('Environment').inputValue(),'');
    await page.getByText('Resolved Environment: SIT',{exact:false}).waitFor();await page.getByText('Origin: Package Manifest',{exact:false}).waitFor();
    await page.getByLabel('Measured duration value').waitFor();assert.equal(await page.getByLabel('Measured duration value').inputValue(),'30');
    await page.getByRole('button',{name:'Back to source Job'}).click();
    await page.goto(base + '#/jobs/'+ids.old);
    await page.getByRole('button', { name: 'Rerun', exact: true }).click();
    await page.getByText('Rerun unavailable for this historical job', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Rerun with same settings', exact: true }).isDisabled(), true);
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + '#/jobs/'+ids.load);
    await page.getByRole('button', { name: 'Rerun', exact: true }).click();
    await page.getByRole('button', { name: 'Rerun with same settings', exact: true }).and(page.locator(':enabled')).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Review must fit a phone viewport');
    // Review dialogs have no dirty form state; dirty navigation is covered in the editors above.
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
