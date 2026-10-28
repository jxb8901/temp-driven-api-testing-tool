const assert=require('node:assert/strict');
const {launchBrowser,startFixture,stopFixture}=require('./browser-support.cjs');
async function main(){
  const fixture=await startFixture(),browser=await launchBrowser();
  try{
    const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[],mutations=[];
    page.on('pageerror',error=>errors.push(error.message));page.on('dialog',()=>{throw new Error('State actions must not open a dialog');});
    const all=Array.from({length:240},(_,i)=>({jobId:'J'+String(i).padStart(16,'0'),command:i%2?'run':'load',executionType:i%2?'run':'load',packageId:'payments',environment:'SIT',principal:i%3?'alice':'load-schedule:PRIVATE',status:'PASS',createdAt:'2026-10-23T00:00:00Z',startedAt:'2026-10-23T00:00:05Z',finishedAt:'2026-10-23T00:00:15Z',pinned:false,deletionState:null,targetGroup:'flow-key',targetIdentity:{type:'flow',id:'common.flow',targets:[{type:'flow',id:'common.flow'}]}}));
    const schedule={id:'SECRET-SCHEDULE-ID',name:'Nightly workload',packageId:'payments',environment:'SIT',executionType:'load',jobType:'load',enabled:true,timezone:'UTC',cron:'0 2 * * *',nextRunAt:'2099-01-01T02:00:00Z',createdBy:'alice',maxConcurrentRuns:1,targetIdentity:{type:'flow',id:'common.flow',workloadCount:1,bindingCount:1,workloads:[{name:'primary',targets:[{type:'flow',id:'common.flow'}],model:'arrivalRate',arrivalRate:'10/s',duration:'5m',warmup:'10s',rampUp:'20s',totalDurationMs:330000}]}};
    await context.route('**/api/v1/**',async route=>{
      const request=route.request(),url=new URL(request.url()),path=decodeURIComponent(url.pathname.split('/api/v1/')[1]||'');const json=(body,status=200)=>route.fulfill({json:body,status});
      if(path==='version')return json({apiVersion:'1',version:'4.2.6',loadSchedulingEnabled:true,authMode:'container'});
      if(path==='auth/capabilities')return route.fallback();
      if(path==='packages')return json({items:[{packageId:'payments'}]});
      if(path==='schedules'||path==='load-schedules')return json({items:[schedule]});
      if(path==='jobs'&&request.method()==='GET'){
        const q=url.searchParams,offset=Number(q.get('offset')||0),limit=Number(q.get('limit')||100);
        let rows=all.filter(job=>(q.get('principalMode')!=='schedule'||job.principal.startsWith('load-schedule:'))&&(!q.get('executionType')||job.command===q.get('executionType'))&&(!q.get('status')||job.status===q.get('status'))&&(q.get('deletion')!=='pending'||job.deletionState==='DELETE_REQUESTED'));
        if(q.get('sort')!=='oldest')rows=rows.slice().reverse();
        return json({items:rows.slice(offset,offset+limit),total:rows.length,offset,limit,principalSuggestions:['alice'],packageSuggestions:['payments'],groups:q.get('groupBy')?[{packageId:'payments',environment:'SIT',targetGroup:'flow-key',total:rows.length,lastCreatedAt:all[0].createdAt,statuses:{PASS:rows.length}}]:[]});
      }
      if(path==='jobs/pin'||path==='jobs/delete'){
        const body=request.postDataJSON();mutations.push({path,body});
        return json({items:body.jobIds.map(id=>{const job=all.find(job=>job.jobId===id);if(path==='jobs/pin'){job.pinned=body.pinned;return {jobId:id,outcome:body.pinned?'PINNED':'UNPINNED'};}if(body.requested&&job.pinned)return {jobId:id,outcome:'PINNED_UNPIN_FIRST'};job.deletionState=body.requested?'DELETE_REQUESTED':null;return {jobId:id,outcome:body.requested?'MARKED':'UNMARKED'};})});
      }
      const match=/^jobs\/([^/]+)(?:\/(.*))?$/.exec(path);
      if(match){const job=all.find(job=>job.jobId===match[1]);if(!job)return json({},404);if(!match[2])return json(job);if(match[2]==='result')return json({job,result:{status:'PASS'}});if(match[2]==='replay')return json({jobId:job.jobId,available:false,reason:'Fixture'});if(match[2]==='artifacts')return json({items:[]});if(match[2]==='events')return route.fulfill({contentType:'text/event-stream',body:'id: 1\nevent: status\ndata: {"status":"PASS"}\n\n'});}
      return route.fallback();
    });
    const base=fixture.origin+'/att/ui/';await page.goto(base+'#/jobs');await page.getByText('Apply to this page: 100 Jobs',{exact:true}).waitFor();
    assert.equal(await page.locator('.att-jobs-table tbody tr').count(),100);assert.equal(await page.locator('.att-jobs-page input[type="checkbox"]').count(),0);assert.equal(await page.locator('.att-jobs-table').getByText(/PRIVATE/).count(),0);
    for(const viewport of [{width:1920,height:1080},{width:1440,height:1000},{width:768,height:900},{width:390,height:844}]) {
      await page.setViewportSize(viewport);
      const heading=await page.getByRole('heading',{name:'Jobs',exact:true}).boundingBox(),first=await page.locator('.att-jobs-table tbody tr').first().boundingBox();
      const distance=first.y-heading.y;
      const verticalBudget=viewport.width===1440?380:viewport.width===768?580:viewport.width===390?700:320;
      assert.ok(distance<verticalBudget,`Jobs filters and toolbar stay within the balanced layout budget at ${viewport.width}px: ${distance}px`);
      const minFilterFieldWidth=await page.evaluate(()=>parseFloat(getComputedStyle(document.documentElement).fontSize)*11);
      for(const field of await page.locator('.att-jobs-filter-grid:not(.att-jobs-more-filters) > label').all()) {
        const box=await field.boundingBox();assert.ok(box.width>=minFilterFieldWidth-1,`Schedule-style filter fields retain their 11rem minimum at ${viewport.width}px: ${box.width}px`);
      }
      for(const control of await page.locator('.att-jobs-filter-grid:not([hidden]) input,.att-jobs-filter-grid:not([hidden]) select').all()) {
        const box=await control.boundingBox();assert.ok(box.x>=0&&box.x+box.width<=viewport.width+1,'No clipped filter fields');
      }
      const actions=page.locator('.att-jobs-row-actions').first();
      await actions.getByRole('button',{name:'Schedule / Reschedule…',exact:true}).waitFor();
      const boxes=await actions.locator('button').evaluateAll(buttons=>buttons.map(button=>{const r=button.getBoundingClientRect();return {x:r.x,y:r.y,right:r.right,bottom:r.bottom,height:r.height};}));
      assert.equal(boxes.length,4,'Pin, Delete, Rerun and Schedule all visible');
      const area=await actions.boundingBox(),cell=await actions.locator('..').boundingBox();
      assert.ok(area.x>=cell.x-1&&area.x+area.width<=cell.x+cell.width+1,'Action group fits its cell');
      assert.ok(await actions.locator('button').evaluateAll(buttons=>buttons.every(button=>button.scrollWidth<=button.clientWidth+1)),'No clipped action text');
      for(const box of boxes)assert.ok(box.x>=area.x-1&&box.right<=area.x+area.width+1&&box.right<=viewport.width+1,'All actions fit the cell/card');
      for(let a=0;a<boxes.length;a++)for(let b=a+1;b<boxes.length;b++)assert.ok(boxes[a].right<=boxes[b].x+1||boxes[b].right<=boxes[a].x+1||boxes[a].bottom<=boxes[b].y+1||boxes[b].bottom<=boxes[a].y+1,'Actions do not overlap');
      const lines=new Set(boxes.map(box=>Math.round(box.y))).size;
      if(viewport.width===1920)assert.equal(lines,1,'Wide layout fits all four actions on one line');
      if(viewport.width===1440) {assert.ok(lines<=2,`Desktop Actions use ${lines} lines`);assert.equal(Math.round(boxes[0].y),Math.round(boxes[2].y),'Pin, Delete and Rerun share first line');}
      console.log(`Actions ${viewport.width}px: ${lines} lines, heights ${boxes.map(box=>Math.round(box.height))}`);
      if(process.env.ATT_UI_SCREENSHOT_DIR)await page.locator('.att-jobs-table tbody tr').first().screenshot({path:`${process.env.ATT_UI_SCREENSHOT_DIR}/jobs-actions-${viewport.width}.png`});
      assert.equal(await page.locator('#jobs-more-filters').isVisible(),false);
      await page.getByRole('button',{name:'More filters',exact:true}).click();
      await page.getByLabel('Deletion state',{exact:true}).selectOption('pending');
      await page.getByText('Deletion: pending',{exact:true}).waitFor();
      await page.getByRole('button',{name:'More filters (1)',exact:true}).click();
      assert.equal(await page.locator('#jobs-more-filters').isVisible(),false);
      await page.getByRole('button',{name:'Clear filters',exact:true}).click();
      await page.getByText('Apply to this page: 100 Jobs',{exact:true}).waitFor();
      console.log(`Jobs heading to first result at ${viewport.width}px: ${Math.round(distance)}px`);
    }
    await page.setViewportSize({width:1440,height:1000});
    await page.getByLabel('Sort',{exact:true}).selectOption('target-desc');
    await page.getByLabel('View',{exact:true}).selectOption('target');
    assert.equal(await page.getByLabel('Sort',{exact:true}).inputValue(),'target-asc');
    assert.equal(await page.getByLabel('Sort',{exact:true}).isDisabled(),true);
    await page.getByRole('button',{name:/240 matching Jobs/}).waitFor();await page.getByRole('button',{name:/240 matching Jobs/}).click();
    await page.getByRole('button',{name:'Pin all ▾',exact:true}).click();await page.getByRole('menuitem',{name:'Pin all on this page',exact:true}).click();await page.getByText(/100 updated/).waitFor();assert.equal(mutations[0].body.jobIds.length,100);assert.equal(mutations[0].body.pinned,true);
    await page.getByRole('button',{name:'Next',exact:true}).click();await page.getByText('Showing 101–200 of 240 Jobs',{exact:true}).first().waitFor();
    await page.getByLabel('Rows per page',{exact:true}).selectOption('50');await page.getByText('Showing 1–50 of 240 Jobs',{exact:true}).first().waitFor();
    await page.getByRole('button',{name:'Pin all ▾',exact:true}).click();await page.getByRole('menuitem',{name:'Unpin all on this page',exact:true}).click();await page.getByText(/50 updated/).waitFor();
    await page.getByRole('button',{name:'Delete all ▾',exact:true}).click();await page.getByRole('menuitem',{name:'Mark all on this page for deletion',exact:true}).click();await page.getByText(/50 updated/).waitFor();assert.equal(mutations[2].body.jobIds.length,50);
    await page.getByRole('button',{name:'Delete all ▾',exact:true}).click();await page.getByRole('menuitem',{name:'Undelete / Unmark pending deletion on this page',exact:true}).click();await page.getByText(/50 updated/).waitFor();assert.equal(mutations[3].body.requested,false);
    for(const viewport of [{width:1440,height:1000},{width:768,height:900},{width:390,height:844}]){
      await page.setViewportSize(viewport);await page.waitForTimeout(250);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Jobs must not overflow');
      await page.addScriptTag({path:require.resolve('axe-core/axe.min.js')});const violations=await page.evaluate(async()=> (await axe.run(document.querySelector('.att-jobs-page'),{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa']}})).violations);assert.deepEqual(violations,[]);
            if(process.env.ATT_UI_SCREENSHOT_DIR)await page.screenshot({path:`${process.env.ATT_UI_SCREENSHOT_DIR}/jobs-${viewport.width}.png`,fullPage:true});
    }
    await page.goto(base+'#/schedules');await page.getByRole('heading',{name:'Schedules',exact:true,level:1}).waitFor();await page.getByText('Configured target rate 10/s',{exact:false}).filter({visible:true}).first().waitFor();assert.equal(await page.getByText(/SECRET-SCHEDULE-ID/).count(),0);
    const id=all[0].jobId;await page.goto(base+`#/jobs/${id}/console?view=events`);await page.getByRole('tab',{name:'Events',exact:true}).waitFor();assert.equal(await page.getByRole('tab',{name:'Events',exact:true}).getAttribute('aria-selected'),'true');
    for(const tab of ['Overview','Live Output','Events','Results','Artifacts','Metrics']){await page.getByRole('tab',{name:tab,exact:true}).click();assert.ok(page.url().includes(id));}
    await page.reload();await page.getByRole('tab',{name:'Metrics',exact:true}).waitFor();assert.equal(await page.getByRole('tab',{name:'Metrics',exact:true}).getAttribute('aria-selected'),'true');
    assert.deepEqual(errors,[]);console.log('Issue #286 Jobs/Schedules desktop/tablet/mobile, axe, page scopes, pending undo, and standalone Details checks passed.');
  }finally{await browser.close();await stopFixture(fixture);}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
