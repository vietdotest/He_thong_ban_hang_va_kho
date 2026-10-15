const {test,expect}=require('@playwright/test'),fs=require('node:fs'),path=require('node:path');
const matrix=require('./matrix.json');
const root=path.resolve(__dirname,'../..');
const accounts=JSON.parse(fs.readFileSync(path.join(root,'.tools/ui-preview-accounts.json'),'utf8').replace(/^\uFEFF/,''));
const fixtures=JSON.parse(fs.readFileSync(path.join(root,'.tools/ui-preview-data.json'),'utf8').replace(/^\uFEFF/,''));
const inventory=require('../../src/test/resources/ui/source-inventory.json');
for(const {view} of inventory)if(!matrix.some(row=>row.view===view))throw Error('JSP chưa có ca kiểm: '+view);
const routeOf=row=>row.route.replace(/\{(\w+)\}/g,(_,key)=>{if(!fixtures[key])throw Error('Thiếu fixture '+key);return fixtures[key];});
async function login(page,actor){
  if(actor==='guest')return;
  const state=JSON.parse(fs.readFileSync(path.join(__dirname,'.auth',actor+'.json'),'utf8'));
  await page.context().addCookies(state.cookies);
}
for(const row of matrix){
  test(`${row.id} ${row.fast?'@fast':''}`,async({page})=>{
    const csp=[],errors=[];page.on('console',msg=>{if(/content.security.policy|violates.*directive|refused to.*(?:script|style|font|image)/i.test(msg.text()))csp.push(msg.text());});page.on('pageerror',error=>errors.push(error.message));
    await login(page,row.actor);
    const route=routeOf(row),response=await page.goto(route);
    expect(response.status()).toBe(row.status);
    expect(new URL(page.url()).pathname).toBe(new URL(route,'http://preview.invalid').pathname);
    await expect(page.locator(row.selector).first()).toBeVisible();
    await expect(page.locator('body')).toHaveAttribute('data-view',row.view);
    const quote=page.locator('[data-quote-status]');
    if(await quote.count()){
      await expect(quote).not.toHaveText('Giá sẽ được kiểm tra lại ngay khi gửi.',{timeout:30000});
      await expect(quote).not.toHaveText('Đang tính báo giá…',{timeout:30000});
      await expect(quote).not.toHaveAttribute('aria-busy','true',{timeout:30000});
    }
    await page.evaluate(()=>document.fonts.ready);
    await page.evaluate(()=>{for(const el of document.querySelectorAll('td,small,time,p,span'))if(el.children.length===0&&/\d{2}:\d{2}|Phiên gần nhất|Đăng nhập gần nhất/.test(el.textContent))el.setAttribute('data-ui-dynamic','');});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect(csp).toEqual([]);expect(errors).toEqual([]);
    if(!process.env.UI_SKIP_SCREENSHOTS)await expect(page).toHaveScreenshot(row.id+'.png',{fullPage:true,mask:[page.locator('time,[data-ui-dynamic]')],style:'*{animation:none!important;transition:none!important;caret-color:transparent!important}'});
    if(process.env.UI_CAPTURE_REVIEW){
      const folder=path.join(root,'.tools/ui-evidence/latest',test.info().project.name);fs.mkdirSync(folder,{recursive:true});
      await page.screenshot({path:path.join(folder,row.id+'.png'),fullPage:true,animations:'disabled',mask:[page.locator('time,[data-ui-dynamic]')],style:'*{animation:none!important;transition:none!important;caret-color:transparent!important}'});
    }
  });
  if(row.axe)test(`${row.id} accessibility @axe`,async({page})=>{
    await login(page,row.actor);await page.goto(routeOf(row));
    const AxeBuilder=require('@axe-core/playwright').default;
    const result=await new AxeBuilder({page}).analyze();
    expect(result.violations.filter(v=>['serious','critical'].includes(v.impact))).toEqual([]);
  });
}
test('mobile menu keyboard @fast',async({page})=>{
  await login(page,'admin');await page.goto('/admin/users');
  if(page.viewportSize().width<1024){const open=page.locator('[data-menu-open]');await open.focus();await page.keyboard.press('Enter');await expect(page.locator('html')).toHaveClass(/menu-open/);await page.keyboard.press('Escape');await expect(page.locator('html')).not.toHaveClass(/menu-open/);await expect(open).toBeFocused();}
});
test('in đơn A4 ngang @print',async({page})=>{
  await login(page,'manager');await page.goto('/orders?action=print&id=2');await page.emulateMedia({media:'print'});
  await expect(page.locator('.sidebar').first()).toBeHidden();
  const buffer=await page.pdf({preferCSSPageSize:true,printBackground:true});
  fs.mkdirSync(path.join(root,'.tools/ui-evidence'),{recursive:true});fs.writeFileSync(path.join(root,'.tools/ui-evidence',test.info().project.name+'-order.pdf'),buffer);
  const boxes=[...buffer.toString('latin1').matchAll(/\/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)\s*\]/g)];
  expect(boxes.length).toBeGreaterThan(0);expect(Number(boxes[0][1])).toBeCloseTo(841.89,0);expect(Number(boxes[0][2])).toBeCloseTo(595.28,0);
});
