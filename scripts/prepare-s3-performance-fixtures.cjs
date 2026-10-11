// Ten distinct synthetic QA actors, never production or portal accounts.
const {execFileSync}=require('node:child_process'),fs=require('node:fs'),path=require('node:path');
const folder=path.resolve(__dirname,'../.tools/s3-evidence'),file=path.join(folder,'performance-fixtures.json');
if(fs.existsSync(file)){console.log('QA performance fixture already exists');process.exit(0);}
function db(sql){return execFileSync('docker',['exec','-i','-e','MYSQL_PWD=root123','codegym-s3-qa-db','mysql','-uroot','-N','-B','sales_inventory'],{input:sql,encoding:'utf8'}).trim();}
const prefix='qa-perf-'+Date.now().toString(36),actors=[];
for(let i=0;i<10;i++){const username=prefix+'-'+i,id=Number(db(`INSERT INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) SELECT '${username}','${username}','${username}@qa.local','${username}@qa.local','QA performance ${i}',password_hash,'ACTIVE' FROM users WHERE username='admin';SELECT LAST_INSERT_ID();`));db(`INSERT INTO user_roles(user_id,role_id) SELECT ${id},id FROM roles WHERE code='ADMIN';`);actors.push({id,username});}
fs.writeFileSync(file,JSON.stringify({createdAt:new Date().toISOString(),actors},null,2));console.log('Prepared10 distinct synthetic QA users; no SMTP sent');
