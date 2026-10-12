// A fresh synthetic account batch only. Never resends an existing successful import.
const {chromium}=require('playwright'),{execFileSync}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path');
const folder=path.resolve(__dirname,'../.tools/s3-evidence'),base='http://127.0.0.1:18093';
const prefix=process.argv[2];
if(!/^qa-[a-z0-9-]{5,30}$/.test(prefix||''))throw Error('Unique synthetic QA prefix required');
function db(sql){return execFileSync('docker',['exec','-i','-e','MYSQL_PWD=root123','codegym-s3-qa-db','mysql','-uroot','-N','-B','sales_inventory'],{input:sql,encoding:'utf8'}).trim();}
function check(label,ok){if(!ok)throw Error(label);console.log('PASS '+label);}
async function main(){
 check('No previous user with new prefix',db(`SELECT COUNT(*) FROM users WHERE username LIKE '${prefix}-u-%'`)==='0');
 check('No other active import',db("SELECT COUNT(*) FROM import_jobs WHERE status IN ('QUEUED','RUNNING')")==='0');
 const mailEnv=JSON.parse(execFileSync('docker',['inspect','codegym-s3-qa-mail-retained','--format','{{json .Config.Env}}'],{encoding:'utf8'}));
 check('QA mailbox keeps all messages',mailEnv.includes('MP_MAX_MESSAGES=0')&&mailEnv.includes('MP_DATABASE=/data/mailpit.db'));
 check('Mailbox bound to loopback QA port',execFileSync('docker',['port','codegym-s3-qa-mail-retained','8025/tcp'],{encoding:'utf8'}).trim()==='127.0.0.1:18096');
 const browser=await chromium.launch();try{
 const context=await browser.newContext({viewport:{width:1440,height:1020}}),page=await context.newPage();
 await page.goto(base+'/login');await page.locator('[name=identity]').fill('admin');await page.locator('[name=password]').fill('admin123');
 await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);
 await page.goto(base+'/admin/users/import?new=1');await page.locator('[name=file]').setInputFiles(path.join(folder,'import-users-retained-5000.xlsx'));
 await Promise.all([page.waitForURL(u=>u.searchParams.has('job')),page.getByRole('button',{name:'Xem trước',exact:true}).click()]);
 const accounts=new URL(page.url()).searchParams.get('job');check('Valid QA job ID',/^[a-f0-9-]{36}$/.test(accounts));
 check('5000 valid preview rows',db(`SELECT COUNT(*) FROM import_job_rows WHERE job_id='${accounts}' AND state='READY'`)==='5000');
 check('Only fresh usernames',db(`SELECT COUNT(*) FROM import_job_rows WHERE job_id='${accounts}' AND c0 LIKE '${prefix}-u-%'`)==='5000');
 const form=await page.locator('.import-confirm').evaluate(el=>Object.fromEntries(new FormData(el)));
 const result=await context.request.post(page.url(),{form,maxRedirects:0});check('Confirmation303',result.status()===303);
 const repeated=await context.request.post(page.url(),{form,maxRedirects:0});check('Repeated confirmation idempotent303',repeated.status()===303);
 const old=JSON.parse(fs.readFileSync(path.join(folder,'import-bulk-jobs.json'),'utf8'));
 fs.writeFileSync(path.join(folder,'import-bulk-retained-jobs.json'),JSON.stringify({prefix,products:old.products,accounts,startedAt:new Date().toISOString(),mailbox:'codegym-s3-qa-mail-retained',smtpPort:11040,mailUiPort:18096},null,2));
 console.log('STARTED fresh retained-mail QA job '+accounts+'; observe with verify-s3-import-bulk.cjs --retained');
 }finally{await browser.close();}
}
main().catch(error=>{console.error('FAIL '+error.name+'; no credentials/mail/token contents logged');process.exitCode=1;});
