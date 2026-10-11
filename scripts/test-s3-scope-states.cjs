// Synthetic QA only. Invalid POSTs and intercepted submissions never save data.
const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path');
const folder=path.resolve(__dirname,'../.tools/s3-evidence'),base='http://127.0.0.1:18094';
const fixture=JSON.parse(fs.readFileSync(path.join(folder,'business-ux-fixture.json'),'utf8'));
if(!/^qa-ux-[a-z0-9]+$/.test(fixture.marker)||fixture.username!==fixture.marker+'-actor'||!fixture.warehouses?.[20]||!fixture.territories?.[20])throw Error('Synthetic fixture required');
const query={q:fixture.marker,warehousePage:'2',warehousePageSize:'20',territoryPage:'2',territoryPageSize:'20'};
const list=base+'/admin/scopes?'+new URLSearchParams(query);
const edit=kind=>list+'&kind='+kind+'&edit='+fixture[kind==='warehouse'?'warehouses':'territories'][20];
function check(label,ok){console.log((ok?'PASS ':'FAIL ')+label);if(!ok)throw Error(label);}
async function login(page,user){
 await page.goto(base+'/login');await page.locator('[name=identity]').fill(user);await page.locator('[name=password]').fill('admin123');
 await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);
}
async function capture(page,state,result){
 result[state]={};
 for(const [device,viewport]of Object.entries({desktop:{width:1440,height:1020},mobile:{width:360,height:900}})){
  await page.setViewportSize(viewport);await page.evaluate(()=>document.fonts.ready);
  const metrics=await page.evaluate(()=>{
   const box=n=>{const r=n.getBoundingClientRect(),s=getComputedStyle(n);return{x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height,font:s.font,gap:s.gap,padding:s.padding,color:s.color,background:s.backgroundColor};};
   const editor=document.querySelector('#scope-editor');
   return{coordinateSpace:'document',height:document.documentElement.scrollHeight,overflow:document.documentElement.scrollWidth>innerWidth,
    sections:[...document.querySelector('main').children].filter(n=>n.getBoundingClientRect().height&&getComputedStyle(n).display!=='none').map(n=>({class:n.className,text:n.innerText,...box(n)})),
    editor:editor?.open?{...box(editor),summary:box(editor.querySelector('summary')),form:box(editor.querySelector('form')),
     fields:[...editor.querySelectorAll('form>.field')].map(n=>{const input=n.querySelector('input:not([type=hidden]),select,textarea');return{label:n.childNodes[0].textContent.trim(),...box(n),input:{...box(input),value:input.value,disabled:input.disabled},notes:[...n.querySelectorAll('small')].map(s=>({text:s.innerText,...box(s)}))};}),actions:box(editor.querySelector('.form-actions'))}:null,
    tables:[...document.querySelectorAll('main table')].map(n=>({...box(n),columns:[...n.querySelectorAll('thead th')].map(c=>({text:c.innerText,...box(c)})),rows:[...n.querySelectorAll('tbody tr')].map(c=>({text:c.innerText,...box(c),cells:[...c.children].map(t=>({text:t.innerText,...box(t)}))}))})),
    alerts:[...document.querySelectorAll('main>.alert')].map(n=>({text:n.innerText,...box(n)})),
    message:document.querySelector('.message-panel')?{...box(document.querySelector('.message-panel')),children:[...document.querySelector('.message-panel').children].filter(n=>n.getBoundingClientRect().height&&getComputedStyle(n).display!=='none').map(n=>({tag:n.tagName,text:n.innerText,...box(n)}))}:null};
  });
  check(state+' '+device+' no overflow',!metrics.overflow);
  if(metrics.editor)for(const field of metrics.editor.fields)check(state+' '+device+' '+field.label+' target44',field.input.height>=44);
  if(metrics.editor&&device==='desktop')check(state+' paired type/code controls align even with validation errors',Math.abs(metrics.editor.fields[0].input.y-metrics.editor.fields[1].input.y)<1);
  result[state][device]=metrics;await page.screenshot({path:path.join(folder,'ux-scopes-'+state+'-'+device+'.png'),fullPage:true});
 }
}
async function main(){
 const browser=await chromium.launch(),result={marker:fixture.marker},errors=[];
 try{
  const context=await browser.newContext(),page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  await login(page,fixture.username);await page.goto(edit('warehouse'));
  check('warehouse edit preserves both independently paged lists',await page.locator('main tbody tr').count()===2&&await page.locator('#scope-editor').getAttribute('open')!==null&&await page.locator('select[name=kind]').isDisabled());
  check('both totals21 and last-page1 are visible',await page.locator('.table-summary').allTextContents().then(a=>a.length===2&&a.every(t=>t.includes('21–21 / 21'))));
  await capture(page,'warehouse',result);
  await page.goto(edit('territory'));check('territory edit has immutable kind and concrete area',await page.locator('select[name=kind]').inputValue()==='territory'&&await page.locator('[name=address]').inputValue()==='Khu vực QA');
  await capture(page,'territory',result);
  await page.goto(list+'&new=1&kind=territory');check('new territory is blank and type is selectable',!await page.locator('select[name=kind]').isDisabled()&&await page.locator('[name=code]').inputValue()===''&&await page.locator('select[name=kind]').inputValue()==='territory');await capture(page,'new-territory',result);
  await page.goto(edit('warehouse'));await page.locator('form.account-form').evaluate(f=>f.noValidate=true);await page.locator('[name=code]').fill('Mã sai !');await page.locator('[name=address]').fill('');
  const [bad]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu kho / địa bàn',exact:true}).click()]);
  check('invalid fields400 preserve raw values and both pages',bad.status()===400&&await page.locator('[name=code]').inputValue()==='Mã sai !'&&await page.locator('[name=address]').inputValue()===''&&await page.locator('form.account-form [name=warehousePage]').inputValue()==='2'&&await page.locator('form.account-form [name=territoryPage]').inputValue()==='2');
  await page.waitForFunction(()=>document.activeElement?.name==='code');check('code and address errors are linked',await page.locator('[name=code]').getAttribute('aria-describedby')==='scope-code-error'&&await page.locator('#scope-address-error').innerText().then(t=>t.includes('địa chỉ')));await capture(page,'error',result);
  await page.goto(edit('warehouse'));await page.locator('form.account-form').evaluate(f=>{f.noValidate=true;f.querySelector('[name=version]').value='-1';});
  const [global]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu kho / địa bàn',exact:true}).click()]);
  check('invalid version400 retains warehouse values without saving',global.status()===400&&await page.locator('main>.alert-error').innerText().then(t=>t.includes('phiên bản')));await capture(page,'global-error',result);
  await page.goto(edit('warehouse'));let posts=0;await page.route('**/*',route=>{if(route.request().method()==='POST'){posts++;return route.abort();}return route.continue();});
  await page.locator('form.account-form').evaluate(f=>{window.s3ScopeSubmit={events:0,duplicates:0};f.addEventListener('submit',e=>{window.s3ScopeSubmit.events++;if(e.defaultPrevented)window.s3ScopeSubmit.duplicates++;e.preventDefault();});});
  await page.getByRole('button',{name:'Lưu kho / địa bàn',exact:true}).click();await page.waitForFunction(()=>document.querySelector('form.account-form')?.dataset.submitting==='true');await page.getByRole('button',{name:'Đang xử lý…',exact:true}).click({force:true});
  check('duplicate submit blocked with no network POST',posts===0&&await page.evaluate(()=>window.s3ScopeSubmit.events===2&&window.s3ScopeSubmit.duplicates===1));await capture(page,'submitting',result);await page.unroute('**/*');
  await page.goto(base+'/admin/scopes?'+new URLSearchParams({...query,q:fixture.marker+'-no-match',warehousePage:'5000',territoryPage:'5000'}));
  check('empty lists have total0 and correctly span five columns',await page.locator('tbody td.empty-state').count()===2&&await page.locator('tbody td.empty-state').evaluateAll(a=>a.every(n=>n.colSpan===5))&&await page.locator('.table-summary').allTextContents().then(a=>a.every(t=>t.includes('0–0 / 0'))));await capture(page,'empty',result);await context.close();
  const deniedContext=await browser.newContext(),denied=await deniedContext.newPage();denied.on('pageerror',e=>errors.push(e.message));await login(denied,fixture.marker+'-reader');
  const csrf=await denied.locator('input[name=_csrf]').first().inputValue();
  check('DIRECTOR cannot browse scope management', (await denied.goto(list)).status()===403);await capture(denied,'forbidden',result);
  check('DIRECTOR cannot use scope management suggestions',(await deniedContext.request.get(base+'/api/lookups/scopes?q='+fixture.marker)).status()===403);
  check('DIRECTOR forged POST is denied before mutation',(await deniedContext.request.post(base+'/admin/scopes',{form:{_csrf:csrf,kind:'warehouse',id:String(fixture.warehouses[20]),version:'-1',code:fixture.marker+'-forged',name:'Không được lưu',address:'QA'}})).status()===403);
  await deniedContext.close();check('no JavaScript errors',errors.length===0);result.errors=errors;result.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(folder,'scope-states-result.json'),JSON.stringify(result,null,2));
 }finally{await browser.close();}
}
main().catch(error=>{console.error('FAIL '+error.message);process.exitCode=1;});
