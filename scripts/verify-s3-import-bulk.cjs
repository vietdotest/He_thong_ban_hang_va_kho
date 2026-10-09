// Resume observation of existing QA jobs; never uploads or confirms a new task.
const {chromium}=require('playwright');
const {execFileSync}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path');
const base='http://127.0.0.1:18093',folder=path.resolve(__dirname,'../.tools/s3-evidence');
const retained=process.argv.includes('--retained'),inspectMail=process.argv.includes('--inspect-retained-mail');
const suffix=retained?'-retained':'',mailPort=retained?18096:18095;
const jobs=JSON.parse(fs.readFileSync(path.join(folder,'import-bulk'+suffix+'-jobs.json'),'utf8'));
if(!/^qa-[a-z0-9-]{5,30}$/.test(jobs.prefix)||![jobs.products,jobs.accounts].every(x=>/^[a-f0-9-]{36}$/.test(x)))throw Error('Không đúng mã QA');
function db(sql){return execFileSync('docker',['exec','-i','-e','MYSQL_PWD=root123','codegym-s3-qa-db','mysql','-uroot','-N','-B','sales_inventory'],{input:sql,encoding:'utf8'}).trim();}
function check(label,ok){console.log((ok?'PASS ':'FAIL ')+label);if(!ok)throw Error('QA assertion');}
async function login(page,user){await page.goto(base+'/login');if(new URL(page.url()).pathname==='/dashboard')return;await page.locator('[name=identity]').fill(user);await page.locator('[name=password]').fill('admin123');await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);}
async function wait(context,route,id){const deadline=Date.now()+4*60*60*1000;let last=0,lastPrint=0;while(Date.now()<deadline){try{const r=await context.request.get(base+route+'?progress=1&job='+id,{timeout:10000});if(!r.ok())throw Error('HTTP');const p=await r.json();if(Date.now()-lastPrint>30000){console.log('PROGRESS '+route+' '+p.status+' success='+p.success_count+' failed='+p.failed_count+' review='+p.review_count);lastPrint=Date.now();}if(!['QUEUED','RUNNING'].includes(p.status))return p;last=Number(p.success_count);}catch(error){console.log('PROGRESS QA temporarily unavailable; last committed='+last);}await new Promise(resolve=>setTimeout(resolve,2000));}throw Error('QA deadline');}
async function main(){const browser=await chromium.launch();try{
 const manager=await browser.newContext({viewport:{width:1440,height:1020}}),page=await manager.newPage();await login(page,'s3-manager');
 const admin=await browser.newContext(),adminPage=await admin.newPage();await login(adminPage,'admin');await adminPage.goto(base+'/admin/users/import?job='+jobs.accounts);check('Job survives new browser session',await adminPage.locator('[data-import-job]').count()===1);
 const product=await wait(manager,'/catalog/products/import',jobs.products);check('5000 products committed',Number(product.success_count)===5000&&Number(product.failed_count)===0&&Number(product.review_count)===0);
 check('Product audit/outcome same transaction',db(`SELECT COUNT(*) FROM import_job_rows r JOIN products p ON p.id=r.entity_id JOIN audit_logs a ON a.object_id=p.id AND a.event_type='PRODUCT_SAVED' WHERE r.job_id='${jobs.products}' AND r.state='SUCCESS'`)==='5000');
 const users=await wait(admin,'/admin/users/import',jobs.accounts);check('5000 accounts committed with real SMTP',Number(users.success_count)===5000&&Number(users.failed_count)===0&&Number(users.review_count)===0);
 check('User audit/outcome same transaction',db(`SELECT COUNT(*) FROM import_job_rows r JOIN users u ON u.id=r.entity_id JOIN audit_logs a ON a.object_id=u.id AND a.event_type='USER_CREATED' WHERE r.job_id='${jobs.accounts}' AND r.state='SUCCESS' AND u.status='PENDING_ACTIVATION'`)==='5000');
 check('5000 successful email journal records',db(`SELECT COUNT(*) FROM import_mail_attempts WHERE job_id='${jobs.accounts}' AND state='SUCCEEDED'`)==='5000');
 const mail=await fetch('http://127.0.0.1:'+mailPort+'/api/v1/search?query='+encodeURIComponent('to:'+jobs.prefix+'-u-')).then(r=>r.json());const mailVerified=Number(mail.messages_count)===5000;
 if(!inspectMail)check('Mailpit received exactly5000 QA activation messages',mailVerified);
 else if(!mailVerified){console.log('NOT VERIFIED retained Mailpit messages='+Number(mail.messages_count)+'; SMTP journal is not mailbox proof');process.exitCode=1;}
 for(const [context,p,route,id,label]of [[manager,page,'/catalog/products/import',jobs.products,'products'],[admin,adminPage,'/admin/users/import',jobs.accounts,'users']]){
  // BCrypt/SMTP runs may outlive the other observer's normal idle session.
  // Renew the QA observer only; never replay upload, confirm or successful rows.
  await login(p,label==='products'?'s3-manager':'admin');
  await p.goto(base+route+'?job='+id+'&page=250&pageSize=20');check(label+' last page20',await p.locator('[data-job-rows] tr').count()===20);
  const report=await context.request.get(base+route+'?job='+id+'&report=1');check(label+' full XLSX report',report.ok()&&(await report.body()).length>10000);
  await p.setViewportSize({width:1440,height:1020});await p.screenshot({path:path.join(folder,'import-bulk'+suffix+'-'+label+'-desktop.png'),fullPage:true});await p.setViewportSize({width:360,height:900});check(label+' mobile no overflow',await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await p.screenshot({path:path.join(folder,'import-bulk'+suffix+'-'+label+'-mobile.png'),fullPage:true});
 }
 const conditions={worker:1,bcryptCost:12,smtp:'Mailpit loopback',database:'QA MySQL8.4',jobTimings:db(`SELECT kind,TIMESTAMPDIFF(SECOND,created_at,finished_at) FROM import_jobs WHERE id IN ('${jobs.products}','${jobs.accounts}') ORDER BY kind`),mailpitMessages:Number(mail.messages_count)};
 fs.writeFileSync(path.join(folder,'import-bulk'+suffix+'-result.json'),JSON.stringify({prefix:jobs.prefix,products:product,accounts:users,finishedAt:new Date().toISOString(),conditions,mailEvidenceVerified:mailVerified,allChecksPassed:mailVerified},null,2));
}finally{await browser.close();}}
main().catch(error=>{console.error('FAIL '+error.name+'; no credentials or mail contents logged');process.exitCode=1;});
