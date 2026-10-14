// Chạy lại các assertion Sprint 3 trên Preview tạm. Chỉ thay đích, mật khẩu thử và nơi lưu evidence.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),{execFileSync,spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),container='codegym-mvp-release-rehearsal';
const info=JSON.parse(execFileSync('docker',['inspect',container],{encoding:'utf8'}))[0];
const binding=info.NetworkSettings.Ports['3306/tcp'];
if(!info.State.Running||info.Config.Labels['nvdo.mvp.rehearsal']!=='temporary'||!info.HostConfig.Tmpfs['/var/lib/mysql']||binding.length!==1||binding[0].HostIp!=='127.0.0.1'||binding[0].HostPort!=='13318')throw Error('Chỉ chạy trên database Preview cô lập.');
const password=info.Config.Env.find(v=>v.startsWith('MYSQL_ROOT_PASSWORD=')).slice('MYSQL_ROOT_PASSWORD='.length);
const query=sql=>execFileSync('docker',['exec','-i','-e','MYSQL_PWD='+password,container,'mysql','-uroot','--default-character-set=utf8mb4','-N','-B','sales_inventory'],{input:sql,encoding:'utf8',cwd:root}).trim();
const credentials=JSON.parse(fs.readFileSync(path.join(root,'.tools/ui-preview-accounts.json'),'utf8'));
const directory=path.join(root,'.tools/ui-s3-copy/scripts'),evidence=path.join(root,'.tools/ui-s3-evidence');fs.mkdirSync(directory,{recursive:true});fs.mkdirSync(evidence,{recursive:true});
fs.copyFileSync(path.join(__dirname,'prepare-ui-regression-fixture.cjs'),path.join(directory,'prepare-ui-regression-fixture.cjs'));
if(process.argv.includes('--prepare')){
 query("INSERT IGNORE INTO territories(code,name) VALUES('S3-HN','Dia ban QA rieng'); INSERT IGNORE INTO categories(code,name) VALUES('S3-UX','Nhom QA rieng');");
 for(const [username,role]of [['s3-manager','SALES_MANAGER'],['s3-sales','SALES']]){
  query(`INSERT IGNORE INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status,must_change_password) SELECT '${username}','${username}','${username}@qa.local','${username}@qa.local','Nguoi QA rieng',password_hash,'ACTIVE',0 FROM users WHERE username='admin'; INSERT IGNORE INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u,roles r WHERE u.username='${username}' AND r.code='${role}';`);
  if(role==='SALES')query(`INSERT IGNORE INTO user_territories(user_id,territory_id) SELECT u.id,t.id FROM users u,territories t WHERE u.username='${username}' AND t.code='S3-HN';`);
 }
 require('./prepare-ui-regression-fixture.cjs')(query,root);
 console.log('Đã chuẩn bị tài khoản, quyền mặc định và dữ liệu QA trong Preview.');process.exit(0);
}
const selected=process.argv.slice(2).filter(a=>/^test-s3-[\w-]+\.cjs$/.test(a));if(!selected.length)throw Error('Truyền các tên test-s3-*.cjs cần chạy.');
const results=[];
for(const name of selected){
 const original=fs.readFileSync(path.join(__dirname,name),'utf8');
 // Guard trong bản chạy cũng kiểm lại container ngay trước từng test.
 const guard=`const uiCP=require('node:child_process'),uiFS=require('node:fs');const uiInfo=JSON.parse(uiCP.execFileSync('docker',['inspect','${container}'],{encoding:'utf8'}))[0];if(uiInfo.Config.Labels['nvdo.mvp.rehearsal']!=='temporary'||!uiInfo.HostConfig.Tmpfs['/var/lib/mysql'])throw Error('Sai Preview');const uiDbPassword=uiInfo.Config.Env.find(v=>v.startsWith('MYSQL_ROOT_PASSWORD=')).slice(20);const uiPassword=JSON.parse(uiFS.readFileSync(${JSON.stringify(path.join(root,'.tools/ui-preview-accounts.json'))},'utf8')).password;\n`;
 const copy=guard+original.replaceAll('http://127.0.0.1:18093','http://127.0.0.1:18087').replaceAll('http://127.0.0.1:18094','http://127.0.0.1:18087').replaceAll("'codegym-s3-qa-db'",`'${container}'`).replaceAll("'MYSQL_PWD=root123'","'MYSQL_PWD='+uiDbPassword").replaceAll("'admin123'",'uiPassword').replaceAll("'../.tools/s3-evidence'",JSON.stringify(evidence)).replaceAll('.tools/s3-evidence/','.tools/ui-s3-evidence/');
 const destination=path.join(directory,name);fs.writeFileSync(destination,copy);
 const args=[];
 if(name==='test-s3-import-jobs.cjs'){
  const prefix='qa-ui-'+Date.now().toString(36),javaSource=path.join(root,'.tools/ui-s3-copy/prepare-ui-import-fixtures.java');
  fs.writeFileSync(javaSource,fs.readFileSync(path.join(__dirname,'prepare-s3-import-fixtures.java'),'utf8').replace('Path.of(".tools","s3-evidence")','Path.of(".tools","ui-s3-evidence")'));
  execFileSync('C:/Program Files/Java/jdk-17/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',path.join(root,'target/ROOT/WEB-INF/classes')+';'+path.join(root,'target/ROOT/WEB-INF/lib/*'),javaSource,prefix],{cwd:root,stdio:'pipe'});
  args.push(prefix);
 }
 const start=Date.now(),log=fs.openSync(path.join(evidence,name+'.log'),'w');
 const result=spawnSync(process.execPath,[destination,...args],{cwd:root,env:{...process.env,NODE_PATH:path.join(root,'scripts/visual/node_modules'),S3_QA_BASE_URL:'http://127.0.0.1:18087',S3_QA_MAIL_URL:'http://127.0.0.1:18095',S3_EVIDENCE_DIR:evidence},stdio:['ignore',log,log],timeout:900000});fs.closeSync(log);
 results.push({name,passed:result.status===0,exitCode:result.status,seconds:Math.round((Date.now()-start)/1000),originalSha256:crypto.createHash('sha256').update(original).digest('hex')});
 console.log((result.status===0?'PASS ':'FAIL ')+name+' ('+results.at(-1).seconds+'s)');
 fs.writeFileSync(path.join(evidence,'results.json'),JSON.stringify({target:'Preview',finishedAt:new Date().toISOString(),results},null,2));
 if(result.status!==0){process.exitCode=1;break;}
}
