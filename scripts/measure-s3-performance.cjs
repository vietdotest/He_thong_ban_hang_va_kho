// Read-only loopback QA measurements; no imports, business writes or mail sends.
const {chromium}=require('playwright'),{execFileSync}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const base='http://127.0.0.1:18094',folder=path.resolve(__dirname,'../.tools/s3-evidence');
const jobs=JSON.parse(fs.readFileSync(path.join(folder,'import-bulk-jobs.json'),'utf8'));
const fixtures=JSON.parse(fs.readFileSync(path.join(folder,'performance-fixtures.json'),'utf8'));
if(fixtures.actors.length!==10||new Set(fixtures.actors.map(a=>a.id)).size!==10||fixtures.actors.some(a=>!/^qa-perf-[a-z0-9]+-[0-9]$/.test(a.username)))throw Error('Ten distinct QA users required');
if(!/^qa-[a-z0-9-]{5,30}$/.test(jobs.prefix))throw Error('Not a synthetic QA prefix');
function db(sql){return execFileSync('docker',['exec','-i','-e','MYSQL_PWD=root123','codegym-s3-qa-db','mysql','-uroot','-N','-B','sales_inventory'],{input:sql,encoding:'utf8'}).trim();}
function percentile(values,p){const sorted=values.slice().sort((a,b)=>a-b);return Math.round(sorted[Math.max(0,Math.ceil(p*sorted.length)-1)]*100)/100;}
async function login(context,username){const page=await context.newPage();await page.goto(base+'/login');await page.locator('[name=identity]').fill(username);await page.locator('[name=password]').fill('admin123');await Promise.all([page.waitForURL('**/dashboard'),page.getByRole('button',{name:'Đăng nhập',exact:true}).click()]);await page.close();}
async function main(){const browser=await chromium.launch(),contexts=[];try{
 for(let i=0;i<10;i++){const context=await browser.newContext();await login(context,fixtures.actors[i].username);contexts.push(context);}
 const routes=[{name:'products5000',url:'/catalog/products?q='+jobs.prefix+'&page=125&pageSize=20',targetMs:1000,suggestion:false},{name:'accountsImportInProgress',url:'/admin/users?q='+jobs.prefix+'&page=50&pageSize=20',targetMs:1000,suggestion:false},{name:'productSuggestion',url:'/api/lookups/products?q='+jobs.prefix+'-p-49',targetMs:500,suggestion:true}];
 const results=[];for(const route of routes){
  await Promise.all(contexts.map(async context=>{const response=await context.request.get(base+route.url);if(response.status()!==200)throw Error('Warmup HTTP '+response.status());const body=await response.body();if(route.suggestion&&!JSON.parse(body.toString()).items?.length)throw Error('Suggestion fixture does not match data');}));
  const timings=[],errors=[];await Promise.all(contexts.map(async(context,user)=>{for(let sample=0;sample<20;sample++){const start=performance.now();try{const response=await context.request.get(base+route.url,{timeout:15000});const body=await response.body();if(response.status()!==200)throw Error('HTTP '+response.status());if(route.suggestion){const data=JSON.parse(body.toString());if(!Array.isArray(data.items)||data.items.length===0||data.items.length>10)throw Error('Invalid suggestion');}timings.push(performance.now()-start);}catch(error){errors.push({user,sample,error:error.message});}}}));
  const result={name:route.name,requests:timings.length,errors,p50Ms:percentile(timings,.5),p95Ms:percentile(timings,.95),maxMs:Math.max(...timings),targetMs:route.targetMs};result.passed=errors.length===0&&timings.length===200&&result.p95Ms<route.targetMs;results.push(result);console.log(JSON.stringify({...result,errors:errors.length}));
 }
 const conditions={base,concurrentSessions:10,distinctAccounts:10,samplesPerSession:20,warmupPerSession:1,measurement:'HTTP response body received; excludes login, browser render and debounce',cpu:os.cpus()[0].model,logicalCpus:os.cpus().length,memoryGb:Math.round(os.totalmem()/1024**3),worker:'one import worker remains active; real BCrypt cost12 and Mailpit SMTP',productCount:Number(db(`SELECT COUNT(*) FROM products WHERE sku LIKE '${jobs.prefix}-p-%'`)),accountCount:Number(db(`SELECT COUNT(*) FROM users WHERE username LIKE '${jobs.prefix}-u-%'`)),importState:db(`SELECT j.status,r.state,COUNT(*) FROM import_jobs j JOIN import_job_rows r ON r.job_id=j.id WHERE j.id='${jobs.accounts}' GROUP BY j.status,r.state ORDER BY r.state`)};
 fs.writeFileSync(path.join(folder,'performance-result.json'),JSON.stringify({measuredAt:new Date().toISOString(),conditions,results},null,2));
 if(results.some(r=>!r.passed))process.exitCode=1;
 }finally{await Promise.all(contexts.map(context=>context.close()));await browser.close();}}
main().catch(error=>{console.error('FAIL '+error.message);process.exitCode=1;});
