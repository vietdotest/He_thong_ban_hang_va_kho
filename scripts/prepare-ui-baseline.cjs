const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),container='codegym-mvp-release-rehearsal';
const info=JSON.parse(execFileSync('docker',['inspect',container],{encoding:'utf8'}))[0];
if(!info.State.Running||info.Config.Labels['nvdo.mvp.rehearsal']!=='temporary'||!info.HostConfig.Tmpfs['/var/lib/mysql']||info.NetworkSettings.Ports['3306/tcp'][0].HostPort!=='13318')throw Error('Đích Preview không hợp lệ.');
const runtime=path.join(root,'.tools/mvp-rehearsal-tomcat/webapps/ROOT');
const inventory=JSON.parse(fs.readFileSync(path.join(root,'src/test/resources/ui/source-inventory.json'),'utf8'));
for(const {view}of inventory){const file=path.join(runtime,'WEB-INF/views',view+'.jsp');let text=fs.readFileSync(file,'utf8');text=text.replace(/<body\b(?![^>]*data-view)/,'<body data-view="'+view+'"');fs.writeFileSync(file,text);}
const assets=path.join(runtime,'assets');fs.mkdirSync(assets,{recursive:true});
fs.writeFileSync(path.join(assets,'__ui_preview_500.jsp'),'<%@ page contentType="text/html;charset=UTF-8" %><% if (true) throw new IllegalStateException("UI Preview test"); %>');
const query=sql=>execFileSync('docker',['exec','-i',container,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 -N -B sales_inventory'],{input:sql,encoding:'utf8'}).trim();
let id=query("SELECT id FROM import_jobs WHERE kind='USER' AND actor_id=1 AND filename='UI-baseline-expired.xlsx' LIMIT 1;");
if(!id){query("INSERT INTO import_jobs(id,kind,actor_id,filename,has_cost,status,confirm_key,total_rows,valid_rows,preview_expires_at,reason) VALUES(UUID(),'USER',1,'UI-baseline-expired.xlsx',0,'EXPIRED',UUID(),1,0,'2026-01-01 00:00:00','Tác vụ thử đã hết hạn.');");id=query("SELECT id FROM import_jobs WHERE kind='USER' AND actor_id=1 AND filename='UI-baseline-expired.xlsx' LIMIT 1;");}
query("UPDATE import_jobs SET reason='Tác vụ thử đã hết hạn.' WHERE kind='USER' AND actor_id=1 AND filename='UI-baseline-expired.xlsx' AND status='EXPIRED';");
fs.writeFileSync(path.join(root,'.tools/ui-preview-data.json'),JSON.stringify({userImportJob:id},null,2));
const credentialsFile=path.join(root,'.tools/ui-preview-accounts.json');const credentials=JSON.parse(fs.readFileSync(credentialsFile,'utf8').replace(/^\uFEFF/,''));credentials.accounts.legacyAdmin='admin';fs.writeFileSync(credentialsFile,JSON.stringify(credentials,null,2));
console.log(`Baseline Preview: ${inventory.length} JSP có data-view; endpoint lỗi chỉ trong runtime tạm.`);
