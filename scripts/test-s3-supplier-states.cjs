// Isolated synthetic QA only: invalid/intercepted/denied POSTs, no successful writes.
const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path');
const folder=path.resolve(__dirname,'../.tools/s3-evidence'),base='http://127.0.0.1:18094';
const fixture=JSON.parse(fs.readFileSync(path.join(folder,'business-ux-fixture.json'),'utf8'));
if(!/^qa-ux-[a-z0-9]+$/.test(fixture.marker)||fixture.username!==fixture.marker+'-actor'||fixture.suppliers?.length!==21)throw Error('Synthetic fixture required');
const list=base+'/catalog/suppliers?'+new URLSearchParams({q:fixture.marker,page:'2',pageSize:'20'}),edit=list+'&id='+fixture.suppliers[20];
function check(label,ok){console.log((ok?'PASS ':'FAIL ')+label);if(!ok)throw Error(label);}
async function login(page,user){await page.goto(base+'/login');await page.locator('[name=identity]').fill(user);await page.locator('[name=password]').fill('admin123');await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);}
async function capture(page,state,result){
 result[state]={};
 for(const [device,viewport]of Object.entries({desktop:{width:1440,height:1020},mobile:{width:360,height:900}})){
  await page.setViewportSize(viewport);await page.evaluate(()=>document.fonts.ready);
  const metrics=await page.evaluate(()=>{
   const box=n=>{const r=n.getBoundingClientRect(),s=getComputedStyle(n);return{x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height,font:s.font,lineHeight:s.lineHeight,gap:s.gap,padding:s.padding,color:s.color,background:s.backgroundColor};};
   const editor=document.querySelector('#editor');
   return{coordinateSpace:'document',height:document.documentElement.scrollHeight,overflow:document.documentElement.scrollWidth>innerWidth,
    sections:[...document.querySelector('main').children].filter(n=>n.getBoundingClientRect().height&&getComputedStyle(n).display!=='none').map(n=>({class:n.className,text:n.innerText,...box(n)})),
    editor:editor?.open?{...box(editor),summary:box(editor.querySelector('summary')),form:box(editor.querySelector('form')),
     fields:[...editor.querySelectorAll('form>.field')].map(n=>{const input=n.querySelector('input:not([type=hidden]),select:not([hidden])');return{label:n.childNodes[0].textContent.trim(),...box(n),input:{...box(input),value:input.value,invalid:input.getAttribute('aria-invalid'),describedBy:input.getAttribute('aria-describedby')},error:{text:n.querySelector('.field-error').innerText,...box(n.querySelector('.field-error'))}};}),actions:box(editor.querySelector('.form-actions'))}:null,
    tables:[...document.querySelectorAll('main table')].map(n=>({...box(n),columns:[...n.querySelectorAll('thead th')].map(c=>({text:c.innerText,...box(c)})),rows:[...n.querySelectorAll('tbody tr')].map(c=>({text:c.innerText,...box(c),cells:[...c.children].map(t=>({text:t.innerText,...box(t)}))}))})),
    alerts:[...document.querySelectorAll('main>.alert')].map(n=>({text:n.innerText,...box(n)}))};
  });
  check(state+' '+device+' no overflow',!metrics.overflow);
  if(metrics.editor)for(const field of metrics.editor.fields)check(state+' '+device+' '+field.label+' target44',field.input.height>=44);
  result[state][device]=metrics;await page.screenshot({path:path.join(folder,'ux-supplier-'+state+'-'+device+'.png'),fullPage:true});
 }
}
async function main(){
 const browser=await chromium.launch(),result={marker:fixture.marker},errors=[];
 try{
  const context=await browser.newContext(),page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());await login(page,fixture.username);await page.goto(edit);
  check('edit outside first page retains page2/count21',await page.locator('tbody tr').count()===1&&await page.locator('.table-summary').innerText().then(t=>t.includes('21–21 / 21'))&&await page.locator('[name=id]').first().inputValue()===String(fixture.suppliers[20]));await capture(page,'default',result);
  await page.goto(list+'&new=1');check('new form does not preselect a warehouse',await page.locator('[name=code]').inputValue()===''&&await page.locator('select[name=warehouse]').inputValue()===''&&await page.locator('.account-form .lookup-control input').inputValue()==='');await capture(page,'new',result);
  await page.goto(edit);await page.locator('form.account-form').evaluate(f=>f.noValidate=true);await page.locator('[name=name]').fill('');
  const [bad]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu nhà cung cấp',exact:true}).click()]);
  check('invalid name400 retains raw value and paging',bad.status()===400&&await page.locator('[name=name]').inputValue()===''&&await page.locator('form.account-form [name=page]').inputValue()==='2');await page.waitForFunction(()=>document.activeElement?.name==='name');check('name error linked and visible',await page.locator('#name-error').innerText().then(t=>t.includes('không hợp lệ'))&&await page.locator('[name=name]').getAttribute('aria-describedby')==='name-error');await capture(page,'error',result);
  await page.goto(edit);await page.locator('form.account-form').evaluate(f=>{f.noValidate=true;for(const [name,value]of [['warehouse','0'],['status','SAI']]){const s=f.querySelector('select[name='+name+']');s.add(new Option(value,value,true,true));s.value=value;}});
  const [selection]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu nhà cung cấp',exact:true}).click()]);
  check('invalid selections400 retain warehouse0 and raw status',selection.status()===400&&await page.locator('select[name=warehouse]').inputValue()==='0'&&await page.locator('select[name=status]').inputValue()==='SAI');
  await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-describedby')==='warehouse-error');check('visible warehouse combobox and status mark errors',await page.locator('.account-form .lookup-control input').getAttribute('aria-invalid')==='true'&&await page.locator('select[name=status]').getAttribute('aria-invalid')==='true');await capture(page,'select-error',result);
  for(const warehouse of ['abc','']){
   await page.goto(edit);await page.locator('form.account-form').evaluate((f,value)=>{f.noValidate=true;const s=f.querySelector('select[name=warehouse]');s.add(new Option(value,value,true,true));s.value=value;},warehouse);
   const [format]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu nhà cung cấp',exact:true}).click()]);
   check('warehouse format/blank400 has field-specific error and raw value',format.status()===400&&await page.locator('select[name=warehouse]').inputValue()===warehouse&&await page.locator('#warehouse-error').innerText()==='Hãy chọn kho hợp lệ.');await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-describedby')==='warehouse-error');
  }
  await page.goto(edit);await page.locator('form.account-form').evaluate(f=>{f.noValidate=true;f.querySelector('[name=version]').value='-1';});
  const [global]=await Promise.all([page.waitForNavigation({waitUntil:'domcontentloaded'}),page.getByRole('button',{name:'Lưu nhà cung cấp',exact:true}).click()]);check('invalid version400 retains values without saving',global.status()===400&&await page.locator('main>.alert-error').innerText().then(t=>t.toLocaleLowerCase('vi').includes('phiên bản')));await capture(page,'global-error',result);
  await page.goto(edit);let posts=0;await page.route('**/*',route=>{if(route.request().method()==='POST'){posts++;return route.abort();}return route.continue();});
  await page.locator('form.account-form').evaluate(f=>{window.s3SupplierSubmit={events:0,duplicates:0};f.addEventListener('submit',e=>{window.s3SupplierSubmit.events++;if(e.defaultPrevented)window.s3SupplierSubmit.duplicates++;e.preventDefault();});});
  await page.getByRole('button',{name:'Lưu nhà cung cấp',exact:true}).click();await page.waitForFunction(()=>document.querySelector('form.account-form')?.dataset.submitting==='true');await page.getByRole('button',{name:'Đang xử lý…',exact:true}).click({force:true});check('duplicate submit blocked without network POST',posts===0&&await page.evaluate(()=>window.s3SupplierSubmit.events===2&&window.s3SupplierSubmit.duplicates===1));await capture(page,'submitting',result);await context.close();
  const readerContext=await browser.newContext(),reader=await readerContext.newPage();reader.on('pageerror',e=>errors.push(e.message));await login(reader,fixture.marker+'-reader');const csrf=await reader.locator('input[name=_csrf]').first().inputValue();await reader.goto(list);
  check('DIRECTOR read-only has no edit/delete controls',await reader.locator('#editor').count()===0&&await reader.getByRole('button',{name:'Xóa',exact:true}).count()===0&&await reader.getByRole('link',{name:'Sửa / ngừng giao dịch',exact:true}).count()===0);await capture(reader,'readonly',result);
  check('DIRECTOR write-only warehouse suggestions403',(await readerContext.request.get(base+'/api/lookups/unitwarehouses?q='+fixture.marker)).status()===403);
  check('DIRECTOR forged save403 before mutation',(await readerContext.request.post(base+'/catalog/suppliers',{form:{_csrf:csrf,id:String(fixture.suppliers[20]),version:'-1',code:fixture.marker+'-forged'}})).status()===403);
  await reader.goto(base+'/catalog/suppliers?'+new URLSearchParams({q:fixture.marker+'-no-match',page:'5000',pageSize:'20'}));check('empty count0/clamped page/colspan8',await reader.locator('.table-summary').innerText().then(t=>t.includes('0–0 / 0'))&&await reader.locator('tbody td.empty-state').evaluate(n=>n.colSpan===8));await capture(reader,'empty',result);await readerContext.close();
  check('no JavaScript errors',errors.length===0);result.errors=errors;result.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(folder,'supplier-states-result.json'),JSON.stringify(result,null,2));
 }finally{await browser.close();}
}
main().catch(error=>{console.error('FAIL '+error.message);process.exitCode=1;});
