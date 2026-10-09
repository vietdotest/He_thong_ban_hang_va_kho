// Synthetic QA only: invalid POSTs, GET conversion, and intercepted submissions.
// No successful business writes and no replay of import jobs.
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path');
const folder=path.resolve(__dirname,'../.tools/s3-evidence');
const base='http://127.0.0.1:18094';
const fixture=JSON.parse(fs.readFileSync(path.join(folder,'business-ux-fixture.json'),'utf8'));
if(!/^qa-ux-[a-z0-9]+$/.test(fixture.marker)||fixture.username!==fixture.marker+'-actor'||!fixture.units?.[20])throw Error('Synthetic fixture required');
const query={product:String(fixture.products[0]),productQuery:fixture.marker,q:fixture.marker,page:'2',pageSize:'20'};
const list=base+'/catalog/units?'+new URLSearchParams(query);
// Do not use #editor here: after a failed POST, adding only a fragment is a
// same-document navigation and retains the previous invalid form in Chromium.
const edit=list+'&edit='+fixture.units[20];
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
   const box=n=>{const r=n.getBoundingClientRect(),s=getComputedStyle(n);return{x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height,font:s.font,gap:s.gap,padding:s.padding};};
   const fields=[...document.querySelectorAll('form.account-form>.field')];
   const editor=document.querySelector('#editor');
   return{coordinateSpace:'document',height:document.documentElement.scrollHeight,overflow:document.documentElement.scrollWidth>innerWidth,
    sections:[...document.querySelector('main').children].filter(n=>n.getBoundingClientRect().height&&getComputedStyle(n).display!=='none').map(n=>({class:n.className,text:n.innerText,...box(n)})),
    editor:editor?.open?{...box(editor),summary:box(editor.querySelector('summary')),form:box(editor.querySelector('form')),fields:fields.map(n=>{
     const input=n.querySelector('input:not([type=hidden]),select:not([hidden])');
     return{label:n.childNodes[0].textContent.trim(),...box(n),input:{...box(input),value:input.value},notes:[...n.querySelectorAll('small')].map(s=>({text:s.innerText,...box(s)}))};
    }),actions:box(editor.querySelector('.form-actions'))}:null,
    columns:[...document.querySelectorAll('thead th')].map(n=>({label:n.innerText,...box(n)})),
    rows:[...document.querySelectorAll('tbody tr')].map(n=>({text:n.innerText,...box(n),cells:[...n.children].map(c=>({text:c.innerText,...box(c)}))})),
    quantity:[...document.querySelectorAll('.unit-convert-form [name=quantity]')].map(n=>({value:n.value,...box(n),label:n.closest('label').innerText,error:document.getElementById(n.getAttribute('aria-describedby'))?.innerText})),
    alert:[...document.querySelectorAll('main>[role=alert],main>[role=status]')].map(n=>({text:n.innerText,...box(n)}))};
  });
  check(state+' '+device+' no overflow',!metrics.overflow);
  for(const field of metrics.quantity)check(state+' '+device+' quantity44',field.height>=44);
  result[state][device]=metrics;
  await page.screenshot({path:path.join(folder,'ux-units-'+state+'-'+device+'.png'),fullPage:true});
 }
}
async function main(){
 const browser=await chromium.launch(),result={marker:fixture.marker},errors=[];
 try{
  const context=await browser.newContext(),page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  await login(page,fixture.username);await page.goto(edit);
  check('edit opens last-page unit without losing filters',await page.locator('#editor').getAttribute('open')!==null&&await page.locator('tbody tr').count()===1&&new URL(page.url()).searchParams.get('page')==='2');
  await capture(page,'default',result);
  await page.locator('form.account-form').evaluate(f=>f.noValidate=true);await page.locator('form.account-form [name=factor]').fill('sai');
  const [invalid]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu đơn vị',exact:true}).click()]);
  check('invalid factor400 keeps raw value and last page',invalid.status()===400&&await page.locator('[name=factor]').inputValue()==='sai'&&await page.locator('tbody tr').count()===1);
  await page.waitForFunction(()=>document.activeElement?.name==='factor');
  check('factor error linked',await page.locator('[name=factor]').getAttribute('aria-describedby')==='unit-factor-error');await capture(page,'error',result);
  await page.goto(edit);await page.locator('form.account-form').evaluate(f=>{f.noValidate=true;f.querySelector('[name=version]').value='-1';});
  const [global]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu đơn vị',exact:true}).click()]);
  check('invalid version400 without saving',global.status()===400&&await page.locator('main>[role=alert]').innerText().then(t=>t.includes('phiên bản')));await capture(page,'global-error',result);
  await page.goto(list);await page.locator('.unit-convert-form [name=quantity]').fill('sai');
  const [badQuantity]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Quy đổi',exact:true}).click()]);
  check('invalid quantity400 preserves raw value and paging',badQuantity.status()===400&&await page.locator('[name=quantity]').inputValue()==='sai'&&new URL(page.url()).searchParams.get('page')==='2');
  await page.waitForFunction(()=>document.activeElement?.name==='quantity');
  check('quantity error does not open unrelated new-unit editor',await page.locator('#editor').getAttribute('open')===null);
  await capture(page,'quantity-error',result);
  await page.goto(list);await page.locator('.unit-convert-form [name=quantity]').fill('2');
  await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Quy đổi',exact:true}).click()]);
  check('GET conversion returns48 base units and version',await page.locator('main>[role=status]').innerText().then(t=>t.includes('48')&&t.includes('phiên bản')));await capture(page,'converted',result);
  await page.goto(edit);let posts=0;await page.route('**/*',route=>{if(route.request().method()==='POST'){posts++;return route.abort();}return route.continue();});
  await page.locator('form.account-form').evaluate(f=>{window.s3UnitSubmit={events:0,duplicates:0};f.addEventListener('submit',e=>{window.s3UnitSubmit.events++;if(e.defaultPrevented)window.s3UnitSubmit.duplicates++;e.preventDefault();});});
  await page.getByRole('button',{name:'Lưu đơn vị',exact:true}).click();await page.waitForFunction(()=>document.querySelector('form.account-form')?.dataset.submitting==='true');
  await page.getByRole('button',{name:'Đang xử lý…',exact:true}).click({force:true});
  check('duplicate submit blocked without network POST',posts===0&&await page.evaluate(()=>window.s3UnitSubmit.events===2&&window.s3UnitSubmit.duplicates===1));await capture(page,'submitting',result);await context.close();
  const readContext=await browser.newContext(),readPage=await readContext.newPage();readPage.on('pageerror',e=>errors.push(e.message));await login(readPage,fixture.marker+'-reader');await readPage.goto(edit);
  check('readonly keeps conversion but hides all write controls',await readPage.locator('#editor').count()===0&&await readPage.getByRole('link',{name:'Sửa',exact:true}).count()===0&&await readPage.getByRole('button',{name:'Xóa',exact:true}).count()===0&&await readPage.getByRole('button',{name:'Quy đổi',exact:true}).count()===1);
  await capture(readPage,'readonly',result);
  const scrollTable=readPage.locator('.table-wrap');
  check('mobile table exposes keyboard focus and accessible label',await scrollTable.getAttribute('tabindex')==='0'&&await scrollTable.getAttribute('aria-label')==='Bảng dữ liệu');
  await scrollTable.focus();
  for(let i=0;i<8;i++)await readPage.keyboard.press('ArrowRight');
  await readPage.waitForFunction(()=>document.querySelector('.table-wrap').scrollLeft>0);
  check('mobile keyboard reveals horizontally clipped conversion column',await scrollTable.evaluate(n=>n.scrollLeft>0));
  await readPage.locator('.unit-convert-form [name=quantity]').focus();
  check('mobile quantity can be reached without business writes',await readPage.locator('.unit-convert-form [name=quantity]').evaluate(n=>{const r=n.getBoundingClientRect();return document.activeElement===n&&r.x>=0&&r.right<=innerWidth;}));
  const forbidden=await readContext.request.get(base+'/api/lookups/unitwarehouses?q='+fixture.marker);
  check('readonly cannot request assigned-warehouse management suggestions',forbidden.status()===403);
  await readPage.goto(base+'/catalog/units?'+new URLSearchParams({...query,q:fixture.marker+'-no-match',page:'5000'}));
  check('empty units count0 and five-column empty row',await readPage.locator('tbody td.empty-state').getAttribute('colspan')==='5'&&await readPage.locator('.table-summary').innerText().then(t=>t.includes('0')));await capture(readPage,'empty',result);await readContext.close();
  check('no JavaScript errors',errors.length===0);result.errors=errors;result.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(folder,'unit-states-result.json'),JSON.stringify(result,null,2));
 }finally{await browser.close();}
}
main().catch(error=>{console.error('FAIL '+error.message);process.exitCode=1;});
