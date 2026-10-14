const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../src/main/webapp/WEB-INF');
function walk(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){
  const file=path.join(dir,entry.name);if(entry.isDirectory()){walk(file);continue;}
  if(!/\.(jsp|jspf|tag)$/.test(file))continue;
  let source=fs.readFileSync(file,'utf8');
  source=source.replace(/＋(?=\s*(?:Thêm|Tạo|Lập))/g,'<ui:icon name="plus"/>').replace(/<span aria-hidden="true">▾<\/span>/g,'<ui:icon name="chevron-down"/>');
  fs.writeFileSync(file,source);
}}
walk(root);
