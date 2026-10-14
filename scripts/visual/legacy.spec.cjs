const {test,expect}=require('@playwright/test'),fs=require('node:fs'),path=require('node:path');
const {PNG}=require('pngjs'),matrix=require('./matrix.json');
const root=path.resolve(__dirname,'../..'),fixtures=JSON.parse(fs.readFileSync(path.join(root,'.tools/ui-preview-data.json'),'utf8'));
async function open(page,row){
 if(row.actor!=='guest')await page.context().addCookies(JSON.parse(fs.readFileSync(path.join(__dirname,'.auth',row.actor+'.json'),'utf8')).cookies);
 const response=await page.goto(row.route.replace(/\{(\w+)\}/g,(_,key)=>fixtures[key]));expect(response.status()).toBe(row.status);
 await expect(page.locator(row.selector).first()).toBeVisible();await page.evaluate(()=>document.fonts.ready);
 await page.evaluate(()=>{for(const el of document.querySelectorAll('td,small,time,p,span'))if(el.children.length===0&&/\d{2}:\d{2}|Phiên gần nhất|Đăng nhập gần nhất/.test(el.textContent))el.setAttribute('data-ui-dynamic','');});
}
async function compare(page,label){
 const options={fullPage:true,animations:'disabled',mask:[page.locator('time,[data-ui-dynamic]')],style:'*{animation:none!important;transition:none!important;caret-color:transparent!important}'};
 const before=PNG.sync.read(await page.screenshot(options));
 const removed=await page.evaluate(()=>{
  let count=0;function strip(sheet){for(let i=sheet.cssRules.length-1;i>=0;i--){const rule=sheet.cssRules[i];if(rule.constructor.name==='CSSLayerBlockRule'&&rule.name==='legacy'){sheet.deleteRule(i);count++;}}}
  for(const sheet of document.styleSheets)strip(sheet);return count;
 });expect(removed,'Phải thử gỡ layer thật').toBeGreaterThan(0);
 const after=PNG.sync.read(await page.screenshot(options));expect({width:after.width,height:after.height},label).toEqual({width:before.width,height:before.height});
 const pixelmatch=(await import('pixelmatch')).default,diff=new PNG({width:before.width,height:before.height});
 const changed=pixelmatch(before.data,after.data,diff.data,before.width,before.height,{threshold:.1}),ratio=changed/(before.width*before.height);
 if(ratio>.01)await test.info().attach(label+'-legacy-diff',{body:PNG.sync.write(diff),contentType:'image/png'});
 expect(ratio,label).toBeLessThanOrEqual(.01);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
}
for(const row of matrix)test(row.id+' @legacy',async({page})=>{await open(page,row);await compare(page,row.id);});
test('menu và dialog @legacy',async({page})=>{
 const row=matrix.find(r=>r.id==='users');await open(page,row);
 if(page.viewportSize().width<1024){await page.locator('[data-menu-open]').click();await expect(page.locator('html')).toHaveClass(/menu-open/);}else await page.locator('.sidebar-user .account-menu').evaluate(el=>el.open=true);
 await compare(page,'menu');
 await page.reload();await page.evaluate(()=>{const d=document.querySelector('.ui-confirm-dialog');if(!d)throw Error('Thiếu dialog');d.querySelector('h2').textContent='Xác nhận thao tác';d.querySelector('p').textContent='Bạn có chắc muốn thực hiện thao tác này?';d.showModal();});
 await compare(page,'dialog');
});
test('bản in @legacy',async({page})=>{await open(page,matrix.find(r=>r.id==='order-print'));await page.emulateMedia({media:'print'});await compare(page,'print');});
