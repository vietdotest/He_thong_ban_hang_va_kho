// GET-only browser acceptance against synthetic QA fixtures; no business writes.
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path');
const folder=path.resolve(__dirname,'../.tools/s3-evidence'),base='http://127.0.0.1:18094';
const fixture=JSON.parse(fs.readFileSync(path.join(folder,'business-ux-fixture.json'),'utf8'));
if(!/^qa-ux-[a-z0-9]+$/.test(fixture.marker)||fixture.username!==fixture.marker+'-actor')throw Error('Synthetic fixture required');
async function login(page,user){await page.goto(base+'/login');await page.locator('[name=identity]').fill(user);await page.locator('[name=password]').fill('admin123');await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);}
async function main(){const browser=await chromium.launch(),result={fixture:fixture.marker},errors=[];try{
const context=await browser.newContext(),page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));await login(page,fixture.username);
for(const state of ['empty','error']){result[state]={};for(const [device,viewport]of Object.entries({desktop:{width:1440,height:1020},mobile:{width:360,height:900}})){
await page.setViewportSize(viewport);const query={userId:String(fixture.actor),type:'SUPPLIER',pageSize:'20',...(state==='empty'?{q:fixture.marker+'-no-match'}:{from:'2026-10-10',to:'2026-10-09'})};
const response=await page.goto(base+'/admin/audit?'+new URLSearchParams(query));if(response.status()!==(state==='error'?400:200))throw Error(state+' status');
if(await page.locator('.audit-table').count()!==0||!(await page.locator('.table-summary').innerText()).includes('/ 0'))throw Error(state+' must be empty');
if(state==='error'){
if(!await page.getByRole('alert').innerText().then(t=>t.includes('Đến ngày phải')))throw Error('Date error missing');
await page.waitForFunction(()=>document.activeElement?.name==='to');
if(await page.locator('input[type=date][name=from]').inputValue()!=='2026-10-10'||await page.locator('input[type=date][name=to]').inputValue()!=='2026-10-09')throw Error('Dates not retained');
if(await page.locator('input[type=date][name=to]').getAttribute('aria-describedby')!=='audit-filter-error')throw Error('Error not linked to date');
if(!await page.locator('#audit-filter-error').evaluate(n=>{const error=n.getBoundingClientRect(),form=n.parentElement.getBoundingClientRect(),first=n.parentElement.querySelector('.field').getBoundingClientRect();return Math.abs(error.width-form.width)<1&&first.y>=error.bottom;}))throw Error('Error must span own row before fields');
}
result[state][device]=await page.evaluate(()=>{const box=n=>{const r=n.getBoundingClientRect(),s=getComputedStyle(n);return{x:r.x,y:r.y,width:r.width,height:r.height,font:s.font,gap:s.gap,padding:s.padding};};return {viewport:[innerWidth,innerHeight],height:document.documentElement.scrollHeight,overflow:document.documentElement.scrollWidth>innerWidth,sections:[...document.querySelector('main').children].filter(n=>n.getBoundingClientRect().height).map(n=>({class:n.className,text:n.innerText,...box(n),children:[...n.children].filter(c=>c.getBoundingClientRect().height).map(c=>({class:c.className,text:c.innerText,...box(c)})),fields:[...n.querySelectorAll('.field,.field-error,.audit-filter-actions')].map(c=>({text:c.innerText,...box(c)}))}))};});
if(result[state][device].overflow)throw Error(state+' '+device+' overflow');await page.screenshot({path:path.join(folder,'ux-audit-'+state+'-'+device+'.png'),fullPage:true});console.log('PASS audit '+state+' '+device);}}
await context.close();const denied=await browser.newContext(),deniedPage=await denied.newPage();await login(deniedPage,fixture.marker+'-warehouse');const response=await deniedPage.goto(base+'/admin/audit?userId='+fixture.actor);if(response.status()!==403)throw Error('Role without AUDIT_READ must receive403');result.permissionDenied=true;console.log('PASS audit direct URL permission403');
if(errors.length)throw Error(errors.join('; '));result.errors=errors;result.finishedAt=new Date().toISOString();fs.writeFileSync(path.join(folder,'audit-states-result.json'),JSON.stringify(result,null,2));
}finally{await browser.close();}}
main().catch(e=>{console.error('FAIL '+e.message);process.exitCode=1;});
