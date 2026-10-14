const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),web=path.join(root,'src/main/webapp'),views=path.join(web,'WEB-INF/views');
const aliases={home:'house',search:'magnifying-glass',filter:'filter',plus:'plus',close:'xmark',check:'check',chevron:'chevron-right',down:'chevron-down',clock:'clock',lock:'lock',logout:'right-from-bracket',menu:'bars',pin:'location-dot',tag:'tag',truck:'truck',users:'users',shield:'shield-halved',box:'box',folder:'folder-tree',unit:'ruler-combined',download:'download',upload:'file-arrow-up','upload-outline':'file-arrow-up',alert:'triangle-exclamation'};
const files=dir=>fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(dir,e.name)):[path.join(dir,e.name)]);
for(const file of files(views).filter(f=>/\.jspf?$/.test(f))){let source=fs.readFileSync(file,'utf8');source=source.replace(/<img\b[^>]*\bsrc="[^"\n]*\/assets\/icons\/([\w-]+)\.svg"[^>]*>/g,(tag,name)=>{const icon=aliases[name.replace(/1$/,'')];if(!icon)throw Error('Chưa ánh xạ icon '+name);const classes=tag.match(/\bclass="([^"]*)"/)?.[1].split(/\s+/).filter(c=>c!=='icon').join(' ')||'';return `<ui:icon name="${icon}"${classes?` cssClass="${classes}"`:''}/>`;});source=source.replace(/<span aria-hidden="true">＋<\/span>/g,'<ui:icon name="plus"/>');fs.writeFileSync(file,source);}
for(const name of ['sidebar','topbar']){let source=fs.readFileSync(path.join(views,'fragments',name+'.jspf'),'utf8').replace(/<%@\s*page[^%]*%>/g,'');source='<%@ tag body-content="empty" pageEncoding="UTF-8" %>\n<%@ taglib prefix="c" uri="jakarta.tags.core" %>\n<%@ taglib prefix="ui" tagdir="/WEB-INF/tags/ui" %>\n'+source;fs.writeFileSync(path.join(web,'WEB-INF/tags/layout',name+'.tag'),source);}
let pagination=fs.readFileSync(path.join(views,'fragments/pagination.jspf'),'utf8').replace(/<%@\s*page[^%]*%>/g,'');
pagination='<%@ tag body-content="empty" pageEncoding="UTF-8" %>\n<%@ taglib prefix="c" uri="jakarta.tags.core" %>\n'+
[['page','java.lang.Object'],['path','java.lang.String'],['pageParameter','java.lang.String'],['sizeParameter','java.lang.String'],['filterValues','java.util.Map'],['extraParameters','java.util.Map']].map(([name,type])=>`<%@ attribute name="${name}" required="${name==='page'}" type="${type}" %>`).join('\n')+
'\n<c:set var="pagination" value="${page}"/><c:set var="paginationPath" value="${path}"/><c:set var="paginationPageParameter" value="${pageParameter}"/><c:set var="paginationSizeParameter" value="${sizeParameter}"/><c:set var="paginationFilterValues" value="${filterValues}"/><c:set var="paginationExtraParameters" value="${extraParameters}"/>\n'+pagination;
fs.writeFileSync(path.join(web,'WEB-INF/tags/ui/pagination.tag'),pagination);
for(const view of ['admin/users/list','catalog/products','orders/editor']){
  const file=path.join(views,view+'.jsp');let source=fs.readFileSync(file,'utf8');
  if(source.includes('<layout:page'))continue;
  const head=source.match(/<head>([\s\S]*?)<\/head>/i)?.[1],body=source.match(/<body([^>]*)>([\s\S]*?)<\/body>/i);if(!head||!body)throw Error('Không tìm được head/body '+view);
  const title=head.match(/<title>([\s\S]*?)<\/title>/)?.[1].split('|')[0].trim()||'Quản lý bán hàng';
  const bodyClass=body[1].match(/class="([^"]*)"/)?.[1]||'app-page';
  const extra=head.replace(/<title>[\s\S]*?<\/title>|<meta\b[^>]*>|<%@\s*include[^%]*assets\.jspf[^%]*%>/g,'').trim();
  let content=body[2].replace(/<%@\s*include[^%]*\/(?:sidebar|topbar)\.jspf[^%]*%>/g,'').replace(/<%@\s*include[^%]*\/pagination\.jspf[^%]*%>/g,'<ui:pagination page="${pagination}" path="${paginationPath}" pageParameter="${paginationPageParameter}" sizeParameter="${paginationSizeParameter}" filterValues="${paginationFilterValues}" extraParameters="${paginationExtraParameters}"/>');
  const preamble=source.slice(0,source.indexOf('<!doctype')).trim();
  fs.writeFileSync(file,preamble+`\n<layout:page title="${title}" view="${view}" bodyClass="${bodyClass}">\n`+(extra?`<jsp:attribute name="head">${extra}</jsp:attribute>\n`:'')+'<jsp:body>\n'+content.trim()+'\n</jsp:body>\n</layout:page>\n');
}
console.log('Đã chuyển icon toàn bộ và layout ba trang mẫu.');
