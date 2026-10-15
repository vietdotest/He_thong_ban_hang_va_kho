// Chỉ chạy với fixture synthetic của Preview được bảo vệ tại 18087.
const {chromium}=require('./visual/node_modules/playwright');
const AxeBuilder=require('./visual/node_modules/@axe-core/playwright').default;
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const baseURL=process.env.UI_PREVIEW_BASE_URL||'http://127.0.0.1:18087',url=new URL(baseURL);
if(!['127.0.0.1','host.docker.internal'].includes(url.hostname)||url.port!=='18087')throw Error('Chỉ chạy trên Preview 18087.');
const root=path.resolve(__dirname,'..'),credentials=JSON.parse(fs.readFileSync(path.join(root,'.tools/ui-preview-accounts.json'),'utf8').replace(/^\uFEFF/,''));
const fixture=JSON.parse(fs.readFileSync(path.join(root,'.tools/business-preview-data.json'),'utf8'));
const output=path.join(root,'.tools/ui-evidence/business');fs.mkdirSync(output,{recursive:true});
const money=n=>Math.round(Number(n)).toLocaleString('vi-VN'),checks=[];
function check(name,condition){assert.ok(condition,name);checks.push(name);}
async function login(page,actor){await page.goto('/login');await page.locator('[name=identity]').fill(credentials.accounts[actor]);await page.locator('[name=password]').fill(credentials.password);await Promise.all([page.waitForURL(u=>['/dashboard','/portal'].includes(u.pathname)),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);}
async function stable(page){const quote=page.locator('[data-quote-status]');if(await quote.count())await page.waitForFunction(()=>{const q=document.querySelector('[data-quote-status]');return q&&q.textContent!=='Giá sẽ được kiểm tra lại ngay khi gửi.'&&!q.textContent.includes('Đang tính báo giá')&&q.getAttribute('aria-busy')!=='true';});await page.evaluate(()=>document.fonts.ready);}
(async()=>{const browser=await chromium.launch();try{
 for(const width of [360,768,1024,1100,1440]){
  const context=await browser.newContext({baseURL,locale:'vi-VN',timezoneId:'Asia/Ho_Chi_Minh',viewport:{width,height:1000},reducedMotion:'reduce',acceptDownloads:true});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  page.on('console',msg=>{if(/content.security.policy|violates.*directive|refused to.*(?:script|style|font|image)/i.test(msg.text()))errors.push(msg.text());});
  const capture=async(name,axe=false,screen=page)=>{await stable(screen);check(`${width}: ${name} không tràn trang`,await screen.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await screen.screenshot({path:path.join(output,`${name}-${width}.png`),fullPage:true,animations:'disabled'});if(axe){const result=await new AxeBuilder({page:screen}).analyze();check(`${width}: ${name} axe`,!result.violations.some(v=>['serious','critical'].includes(v.impact)));}};
  await login(page,'manager');
  const filters=new URLSearchParams({dealer:String(fixture.dealer),staff:String(fixture.staff),territory:String(fixture.territory),from:fixture.day,to:fixture.day,q:fixture.prefix,pageSize:'20'});
  await page.goto('/orders?'+filters);await capture('orders',true);
  check(`${width}: tổng toàn bộ kết quả lọc`,(await page.locator('main').innerText()).includes(money(fixture.filteredTotal)+' đ'));
  const next=page.locator('.pagination-footer a[href*="page=2"]').first();check(`${width}: fixture có trang thứ hai`,await next.count()>0);await next.click();check(`${width}: phân trang giữ bộ lọc`,['dealer','staff','territory','from','to','q'].every(k=>new URL(page.url()).searchParams.get(k)===filters.get(k)));
  await page.getByRole('link',{name:'Mở đơn',exact:true}).first().click();await page.getByRole('link',{name:'Danh sách đơn',exact:true}).click();check(`${width}: quay lại giữ bộ lọc`,['dealer','staff','territory','from','to','q'].every(k=>new URL(page.url()).searchParams.get(k)===filters.get(k)));
  await page.goto('/orders?id='+fixture.draft+'&'+filters);await stable(page);
  if(width===360){await Promise.all([page.waitForURL(u=>u.searchParams.get('notice')==='saved'),page.locator('[data-save-order]').click()]);check('Lưu nháp giữ ngữ cảnh lọc',new URL(page.url()).searchParams.get('staff')===filters.get('staff')&&new URL(page.url()).searchParams.get('from')===fixture.day);await stable(page);}
  check(`${width}: lượng nhập bỏ số 0 thừa`,await page.locator('input[name=quantity]').first().inputValue()==='1.25');
  check(`${width}: hệ số dễ đọc`,(await page.locator('select[name=unit] option:checked').first().innerText()).includes('(×1)'));
  const line=await page.locator('[data-line-money]').first().innerText(),credit=await page.locator('[data-credit-state]').innerText();
  check(`${width}: lượng cơ sở định dạng VN`,line.includes('Cơ sở: 1,25')&&!line.includes('.000000000000'));
  check(`${width}: hạn mức định dạng tiền`,credit.includes('2.000.000 đ')&&!credit.includes('2000000.0000'));
  const denied=await context.request.post('/orders',{form:{action:'save',id:String(fixture.draft),_csrf:'invalid'}});check(`${width}: CSRF vẫn chặn`,denied.status()===403);
  await capture('order-draft',true);
  const warehouseContext=await browser.newContext({baseURL,locale:'vi-VN',timezoneId:'Asia/Ho_Chi_Minh',viewport:{width,height:1000}}),warehousePage=await warehouseContext.newPage();await login(warehousePage,'warehouse');await warehousePage.goto('/fulfillment/shipments?order='+fixture.sourceOrder+'&id='+fixture.shipment);
  check(`${width}: mã đơn trên màn kho`,(await warehousePage.locator('main').innerText()).includes(fixture.number)&&!(await warehousePage.locator('main').innerText()).includes('ĐH-'+fixture.sourceOrder));await capture('shipments',false,warehousePage);await warehouseContext.close();
  await page.goto('/inventory/stock?warehouse='+fixture.warehouse+'&category='+fixture.category+'&state=AVAILABLE');check(`${width}: nhóm cha gồm SKU nhóm con`,(await page.locator('table').innerText()).includes(fixture.sku));await capture('stock',true);
  await page.goto('/inventory/stock?warehouse='+fixture.warehouse+'&category='+fixture.category+'&state=UNAVAILABLE');check(`${width}: lọc hết khả dụng loại SKU còn tồn`,!(await page.locator('table').innerText()).includes(fixture.sku));
  await page.goto('/accounting/debt?dealer='+fixture.dealer);await capture('debt',true);
  const aging=page.locator('section').filter({has:page.getByRole('heading',{name:'Tuổi nợ theo ngày đến hạn',exact:true})});const amounts=await aging.locator('dd').allInnerTexts();
  const keys=['not_due','due_today','days_1_30','days_31_60','days_61_90','days_over_90'];check(`${width}: sáu nhóm tuổi khớp sổ`,keys.every((k,i)=>amounts[i]===money(fixture.debt[k])+' đ'));
  await page.locator('#aging-days_31_60 summary').click();check(`${width}: mở hóa đơn thuộc nhóm tuổi`,(await page.locator('#aging-days_31_60').innerText()).includes('Còn phải thu'));
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'Xuất Excel',exact:true}).click();const file=await download;await file.saveAs(path.join(output,`cong-no-${width}.xlsx`));check(`${width}: tải XLSX thật`,fs.readFileSync(path.join(output,`cong-no-${width}.xlsx`)).subarray(0,2).toString()==='PK');
  await page.goto('/reports/sales?dealer='+fixture.dealer+'&from='+fixture.day+'&to='+fixture.day);await capture('sales-margin',true);
  check(`${width}: lãi gộp đúng snapshot`,(await page.locator('main').innerText()).includes(money(fixture.expectedProfit)+' đ'));
  await page.goto('/reports/sales?dealer='+fixture.missingDealer+'&from='+fixture.day+'&to='+fixture.day);check(`${width}: chứng từ thiếu giá vốn được công bố`,(await page.locator('main').innerText()).includes('Chưa có giá vốn lịch sử'));await capture('sales-missing');
  await page.goto('/dealers?q='+encodeURIComponent(fixture.dealerCode));check(`${width}: đại lý có giao dịch không có nút Xóa`,await page.locator('tbody').getByRole('button',{name:'Xóa',exact:true}).count()===0);await capture('dealers');
  await page.goto('/dealers/credit?dealer='+fixture.dealer);check(`${width}: hướng dẫn tín dụng không còn Sprint 3`,!(await page.locator('main').innerText()).includes('Sprint 3 chưa'));await capture('credit');
  if(width===360){await page.locator('[data-menu-open]').focus();await page.keyboard.press('Enter');check('menu mở bằng bàn phím',await page.locator('html').evaluate(el=>el.classList.contains('menu-open')));await page.keyboard.press('Escape');check('Escape trả focus menu',await page.locator('[data-menu-open]').evaluate(el=>document.activeElement===el));}
  check(`${width}: không có lỗi JS hoặc CSP`,errors.length===0);await context.close();
 }
 const restricted=await browser.newContext({baseURL,viewport:{width:1440,height:1000}}),page=await restricted.newPage();await login(page,'accountant');await page.goto('/reports/sales?dealer='+fixture.dealer+'&from='+fixture.day+'&to='+fixture.day);check('Kế toán không nhận HTML giá vốn/lãi gộp',!(await page.content()).match(/known_cost|gross_profit|margin_percent|Lãi gộp bán hàng|Giá vốn thuần/));await restricted.close();
 fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({passed:true,checks,completedAt:new Date().toISOString()},null,2));console.log(`Đạt ${checks.length} kiểm tra UI nghiệp vụ; ảnh và Excel tại .tools/ui-evidence/business.`);
}finally{await browser.close();}})().catch(error=>{fs.writeFileSync(path.join(output,'results.json'),JSON.stringify({passed:false,checks,error:error.message},null,2));console.error(error.message);process.exitCode=1;});
