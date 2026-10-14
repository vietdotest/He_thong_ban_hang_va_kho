const fs=require('node:fs'),path=require('node:path');
const postcss=require('./visual/node_modules/postcss');
const root=path.resolve(__dirname,'..'),web=path.join(root,'src/main/webapp');
const inventory=require('../src/test/resources/ui/source-inventory.json');
const pageSheets=['dealers','orders','mvp','import-jobs'];let combined='';
for(const sheet of pageSheets){
  const ast=postcss.parse(fs.readFileSync(path.join(web,'assets/css',sheet+'.css'),'utf8'));
  const prefix=`:where(body[data-ui-css~="${sheet}"])`;
  ast.walkRules(rule=>{
    for(let parent=rule.parent;parent;parent=parent.parent)if(parent.type==='atrule'&&/keyframes$/i.test(parent.name))return;
    rule.selectors=rule.selectors.map(selector=>{
      if(/^body\b/.test(selector))return selector.replace(/^body\b/,prefix);
      if(/^\.document-print\b/.test(selector))return prefix+selector;
      return prefix+' '+selector;
    });
  });
  combined+=`/* Chỉ áp dụng cho những JSP trước đây nạp ${sheet}. */\n`+ast.toString()+'\n';
}
const out=path.join(root,'src/main/tailwind/legacy');fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,'pages.css'),combined);
for(const item of inventory){
  const file=path.join(web,'WEB-INF/views',item.view+'.jsp');let source=fs.readFileSync(file,'utf8');
  const scopes=item.css.map(css=>css.replace(/\.css$/,'')).filter(css=>pageSheets.includes(css)).join(' ');
  if(source.includes('<layout:page')){
    source=source.replace(/<layout:page\b([^>]*)>/,(tag,attrs)=>attrs.includes(' css=')?tag:`<layout:page${attrs} css="${scopes}">`);
  }else{
    source=source.replace(/<body\b([^>]*)>/,(tag,attrs)=>`<body${attrs.replace(/\sdata-view="[^"]*"|\sdata-ui-css="[^"]*"/g,'')} data-view="${item.view}" data-ui-css="${scopes}">`);
  }
  source=source.replace(/<link\b[^>]*href="[^"\n]*\/assets\/css\/(?:dealers|orders|mvp|import-jobs)\.css[^"\n]*"[^>]*>/g,'');
  fs.writeFileSync(file,source);
}
console.log('CSS riêng trang đã giữ phạm vi qua data-ui-css; mọi trang có data-view.');
