const {execFileSync}=require('node:child_process');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const base='http://127.0.0.1:18093';
const container='codegym-s3-qa-db';
const evidence=path.resolve(__dirname,'../.tools/s3-evidence');fs.mkdirSync(evidence,{recursive:true});
function db(sql){return execFileSync('docker',['exec','-i','-e','MYSQL_PWD=root123',container,'mysql','-uroot','--default-character-set=utf8mb4','-N','-B','sales_inventory'],{input:sql,encoding:'utf8'}).trim();}
function check(label,value){if(!value)throw Error(label);console.log('PASS '+label);}
async function login(page,name){await page.goto(base+'/login');await page.locator('[name=identity]').fill(name);await page.locator('[name=password]').fill('admin123');await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);}
async function main(){
  const health=await fetch(base+'/health');check('QA health',health.ok);
  db("INSERT IGNORE INTO categories(code,name) VALUES('S3-UX','Nhóm QA'); INSERT IGNORE INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status,must_change_password) SELECT 's3-manager','s3-manager','s3-manager@test.local','s3-manager@test.local','Quản lý QA',password_hash,'ACTIVE',0 FROM users WHERE username='admin'; INSERT IGNORE INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u,roles r WHERE u.username='s3-manager' AND r.code='SALES_MANAGER';");
  const digits='(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)';
  const numbers=`SELECT a.n+10*b.n+100*c.n+1000*d.n n FROM ${digits} a CROSS JOIN ${digits} b CROSS JOIN ${digits} c CROSS JOIN ${digits} d`;
  db(`INSERT IGNORE INTO products(sku,name,category_id,base_unit,packaging,status) SELECT CONCAT('UX-',LPAD(n,4,'0')),CONCAT('Nước ngọt QA ',n),c.id,'Lon','Thùng 24 lon','ACTIVE' FROM (${numbers}) t CROSS JOIN categories c WHERE n<=5000 AND c.code='S3-UX';`);
  db(`INSERT IGNORE INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status,must_change_password) SELECT CONCAT('s3-user-',n),CONCAT('s3-user-',n),CONCAT('s3-user-',n,'@test.local'),CONCAT('s3-user-',n,'@test.local'),CONCAT('Người QA ',n),u.password_hash,'ACTIVE',0 FROM (${numbers}) t CROSS JOIN users u WHERE n<5000 AND u.username='admin';`);
  const browser=await chromium.launch({headless:true});
  try{
    const context=await browser.newContext({viewport:{width:1440,height:1000}});const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await login(page,'s3-manager');
    let response=await page.goto(base+'/catalog/products?q=UX-&page=99999&pageSize=20');check('Product last page HTTP 200',response.status()===200);
    check('Last SKU present',await page.locator('.product-table').getByText('UX-5000',{exact:true}).count()===1);
    check('Pager bounded',await page.locator('.pagination a').count()<=9);
    check('Range reflects all 5001 products',(await page.locator('.table-summary').last().innerText()).includes('5001'));
    const prev=page.getByRole('link',{name:'Trước',exact:true});check('Pager points to servlet',!(await prev.getAttribute('href')).includes('WEB-INF'));
    await prev.click();check('Previous page stays filtered',page.url().includes('q=UX-'));
    for(const size of [20,50,100]){await page.goto(base+`/catalog/products?q=UX-&pageSize=${size}`);check('Rows '+size,await page.locator('.product-table tbody tr').count()===size);}
    response=await page.goto(base+'/catalog/products?page=999999999999999999');check('Invalid large page not 500',response.status()===200);
    await page.goto(base+'/catalog/products');await page.locator('input[name=q]').fill('UX-5000');await page.locator('.lookup-option').first().waitFor();check('Lookup finds SKU outside first page',(await page.locator('.lookup-menu').innerText()).includes('UX-5000'));
    await page.locator('input[name=q]').press('ArrowDown');await page.locator('input[name=q]').press('Enter');check('Keyboard selects lookup',await page.locator('input[name=q]').inputValue()==='UX-5000');
    await page.goto(base+'/catalog/units');await page.locator('.lookup-control input').last().fill('UX-5000');await page.locator('.lookup-option').first().click();check('Remote product selection',await page.locator('select[name=product]').inputValue()===db("SELECT id FROM products WHERE sku='UX-5000'"));
    const forbidden=await context.request.get(base+'/api/lookups/users?q=s3-user');check('Sales manager cannot lookup internal users',forbidden.status()===403);
    const api=await context.request.get(base+'/api/lookups/products?q=Nuoc%20ngot');const payload=await api.json();check('Accent-insensitive lookup and limit',payload.items.length===10);check('No cost fields in lookup',!JSON.stringify(payload).includes('cost'));
    await page.goto(base+'/catalog/products?q=UX-&pageSize=20');await page.screenshot({path:path.join(evidence,'products-desktop.png'),fullPage:true});
    await page.setViewportSize({width:360,height:900});await page.screenshot({path:path.join(evidence,'products-mobile.png'),fullPage:true});check('Mobile no horizontal overflow',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await context.clearCookies();await login(page,'admin');await page.goto(base+'/admin/users?q=s3-user-&pageSize=100');check('User page size 100',await page.locator('tbody tr').count()===100);check('User pager bounded',await page.locator('.pagination a').count()<=9);
    check('No JS errors',errors.length===0);
  }finally{await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
