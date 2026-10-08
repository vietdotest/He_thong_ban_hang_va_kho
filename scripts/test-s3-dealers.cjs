const {execFileSync}=require('node:child_process');
const {chromium}=require('playwright');
const path=require('node:path');
const base='http://127.0.0.1:18093',container='codegym-s3-qa-db';
function db(sql){return execFileSync('docker',['exec','-i','-e','MYSQL_PWD=root123',container,'mysql','-uroot','--default-character-set=utf8mb4','-N','-B','sales_inventory'],{input:sql,encoding:'utf8'}).trim();}
function check(label,condition){if(!condition)throw Error(label);console.log('PASS '+label);}
async function login(page,user){await page.goto(base+'/login');await page.locator('[name=identity]').fill(user);await page.locator('[name=password]').fill('admin123');await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);}
async function main(){
 db("INSERT IGNORE INTO territories(code,name,address) VALUES('S3-HN','Hà Nội QA','Khu vực thử nghiệm'); INSERT IGNORE INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status,must_change_password) SELECT 's3-sales','s3-sales','s3-sales@test.local','s3-sales@test.local','Nhân viên QA',password_hash,'ACTIVE',0 FROM users WHERE username='admin'; INSERT IGNORE INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u,roles r WHERE u.username='s3-sales' AND r.code='SALES';");
 const browser=await chromium.launch({headless:true});
 try{const context=await browser.newContext({viewport:{width:1440,height:1020}}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await login(page,'s3-manager');
 let response=await page.goto(base+'/dealers');check('Dealer list compiles',response.status()===200);
 const token=await page.locator('input[name=_csrf]').first().inputValue();
 const group=db('SELECT id FROM customer_groups LIMIT 1'),territory=db("SELECT id FROM territories WHERE code='S3-HN'"),staff=db("SELECT id FROM users WHERE username='s3-manager'");
 const code='WEB-'+Date.now();const create=await context.request.post(base+'/dealers',{form:{_csrf:token,code,name:'Đại lý QA browser',taxCode:'0101234567',phone:'0912345678',group,territory,staff,warehouse:'',status:'ACTIVE',version:'0'},maxRedirects:0});check('Create via protected POST',create.status()===303);
 const id=db(`SELECT id FROM dealers WHERE code='${code}'`);await page.goto(base+'/dealers?id='+id);check('Saved form restores fields',await page.locator('input[name=code]').inputValue()===code);check('Form two columns desktop',await page.locator('.dealer-profile-form').evaluate(n=>getComputedStyle(n).gridTemplateColumns.split(' ').length===2));
 const invalid=await context.request.post(base+'/dealers',{form:{_csrf:token,code,name:'Tên giữ lại',taxCode:'BAD',phone:'0912345678',group,territory,staff,warehouse:'',status:'ACTIVE',version:'0'}});check('Validation HTTP 400',invalid.status()===400);check('Retains submitted value',(await invalid.text()).includes('Tên giữ lại'));
 await page.screenshot({path:path.resolve('.tools/s3-evidence/dealers-form-desktop.png'),fullPage:true});await page.setViewportSize({width:360,height:900});check('Mobile form one column',await page.locator('.dealer-profile-form').evaluate(n=>getComputedStyle(n).gridTemplateColumns.split(' ').length===1));check('Mobile no overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.resolve('.tools/s3-evidence/dealers-form-mobile.png'),fullPage:true});
 await context.clearCookies();await login(page,'s3-sales');response=await page.goto(base+'/dealers');check('Assigned salesperson count zero',(await page.locator('.table-summary').innerText()).includes('0–0'));response=await page.goto(base+'/dealers?id='+id);check('Foreign dealer URL forbidden',response.status()===403);
 await context.clearCookies();await login(page,'admin');response=await page.goto(base+'/dealers');check('Admin no default dealer permission',response.status()===403);check('No JS exceptions',errors.length===0);
 }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
