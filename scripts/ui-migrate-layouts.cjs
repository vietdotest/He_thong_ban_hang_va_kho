// Chuyển lần lượt theo tám đợt; không sửa form hoặc nội dung nghiệp vụ.
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),views=path.join(root,'src/main/webapp/WEB-INF/views');
const batches=[
 ['error/403','error/404','error/500','message','dashboard'],
 ['auth/login','auth/forgot-password','auth/reset-password','auth/activate'],
 ['orders/list','orders/editor','orders/print'],
 ['imports/import','imports/job','portal/home','portal/accounts'],
 ['account/profile','account/change-password','admin/users/list','admin/users/form','admin/users/status','admin/roles','admin/scopes','admin/assignments','admin/audit'],
 ['catalog/categories','catalog/products','catalog/suppliers','catalog/units'],
 ['dealers/list','dealers/addresses','dealers/credit','dealers/handover','dealers/status','pricing/prices','pricing/discounts','pricing/history'],
 ['inventory/card','inventory/receipts','inventory/stock','fulfillment/shipments','accounting/debt','accounting/invoices','accounting/payments','accounting/returns','reports/sales']
];
const batch=Number(process.argv[2]);if(!Number.isInteger(batch)||batch<1||batch>8)throw Error('Chọn đợt 1–8.');
const pager='<ui:pagination page="${pagination}" path="${paginationPath}" pageParameter="${paginationPageParameter}" sizeParameter="${paginationSizeParameter}" filterValues="${paginationFilterValues}" extraParameters="${paginationExtraParameters}"/>';
for(const view of batches[batch-1]){
 const file=path.join(views,view+'.jsp');let source=fs.readFileSync(file,'utf8');
 if(!source.includes('<layout:page')){
  const start=source.search(/<!doctype/i),head=source.match(/<head>([\s\S]*?)<\/head>/i),body=source.match(/<body([^>]*)>([\s\S]*?)<\/body>/i);
  if(start<0||!head||!body)throw Error('Không tìm được cấu trúc trang '+view);
  const title=head[1].match(/<title>([\s\S]*?)<\/title>/i)?.[1].split('|')[0].trim().replace(/<c:out value="([^"]*)"\/>/g,'$1');if(!title)throw Error('Thiếu title '+view);
  const css=body[1].match(/data-ui-css="([^"]*)"/)?.[1]||'',bodyClass=body[1].match(/class="([^"]*)"/)?.[1]||'app-page';
  let preamble=source.slice(0,start).trim(),content=body[2];
  // currentUser của trang lỗi trước đây được đặt trước sidebar, giữ phạm vi trong JSP gọi tag.
  if(view.startsWith('error/')){content=content.replace(/<c:set var="currentUser"[^>]*\/>/,match=>{preamble+='\n'+match;return '';});}
  content=content.replace(/<c:if test="\$\{not empty currentUser\}">\s*<%@\s*include[^%]*sidebar\.jspf[^%]*%>\s*<%@\s*include[^%]*topbar\.jspf[^%]*%>\s*<\/c:if>/g,'');
  content=content.replace(/<%@\s*include[^%]*(?:sidebar|topbar)\.jspf[^%]*%>/g,'');
  const bare=view.startsWith('auth/')||view==='orders/print'?'true':view.startsWith('error/')||view==='message'?'${empty currentUser}':'false';
  const extra=head[1].replace(/<title>[\s\S]*?<\/title>|<meta\b[^>]*(?:charset=|name="viewport")[^>]*>|<%@\s*include[^%]*assets\.jspf[^%]*%>/gi,'').trim();
  const suffix=source.slice(source.lastIndexOf('</html>')+7).trim();
  source=preamble+`\n<layout:page title="${title}" view="${view}" bodyClass="${bodyClass}" css="${css}" bare="${bare}">\n`+(extra?`<jsp:attribute name="head">${extra}</jsp:attribute>\n`:'')+'<jsp:body>\n'+content.trim()+'\n</jsp:body>\n</layout:page>\n'+(suffix?suffix+'\n':'');
 }
 source=source.replace(/<%@\s*include[^%]*\/(?:fragments\/)?pagination\.jspf[^%]*%>/g,pager);
 source=source.replace(/<%@\s*include[^%]*link-history-pagination\.jspf[^%]*%>/g,'<ui:pagination page="${linkHistoryPagination}" path="/dealers/accounts" pageParameter="historyPage" sizeParameter="historyPageSize" filterNames="${[\'userId\']}" label="Lịch sử" sizeLabel="Số dòng lịch sử" ariaLabel="Phân trang lịch sử liên kết"/>');
 source=source.replace(/\/assets\/js\/([\w-]+\.js)(?:\?v=[^"\s]*)?(?=")/g,'/assets/js/$1?v=${applicationScope.assetVersion}');
 fs.writeFileSync(file,source);
}
console.log(`Đợt ${batch}: ${batches[batch-1].length} JSP dùng layout chung.`);
