(() => {
  'use strict';
  const root=document.querySelector('[data-import-job]');if(!root)return;
  let stopped=false, timer, controller;
  const active=status=>status==='QUEUED'||status==='RUNNING';
  const element=(tag,text,className)=>{const el=document.createElement(tag);el.textContent=text==null?'':String(text);if(className)el.className=className;return el;};
  const columns=[...root.querySelectorAll('[data-job-column]')].map(el=>el.textContent);
  const user=columns[0]==='Tên đăng nhập';
  const titles={PREVIEW:'Xem trước dữ liệu',QUEUED:'Tác vụ đang chờ xử lý',RUNNING:'Đang xử lý tác vụ',COMPLETED:'Tác vụ đã xử lý xong',STOPPED:'Tác vụ đã dừng',EXPIRED:'Xem trước đã hết hạn'};
  const reportError=text=>{const el=root.querySelector('[data-job-poll-error]');el.textContent=text;el.hidden=!text;};
  function updateRows(rows){
    const body=root.querySelector('[data-job-rows]');const expanded=new Set([...body.querySelectorAll('tr')].filter(el=>el.querySelector('details[open]')).map(el=>el.firstElementChild.textContent));
    body.replaceChildren();
    for(const row of rows){const tr=document.createElement('tr');for(const value of [row.number,row.cells[0],row.cells[user?2:1],row.operation])tr.append(element('td',value));
      const td=document.createElement('td');td.append(element('span',row.label,'badge '+(row.state==='SUCCESS'?'badge-success':['INVALID','FAILED','REVIEW'].includes(row.state)?'badge-danger':'')),element('p',row.error,'field-error'));
      const details=document.createElement('details');details.className='row-details';details.open=expanded.has(String(row.number));details.append(element('summary','Thông tin dòng'));const dl=document.createElement('dl');columns.forEach((label,i)=>{const div=document.createElement('div');div.append(element('dt',label),element('dd',row.cells[i]));dl.append(div);});details.append(dl);td.append(details);tr.append(td);body.append(tr);
    }
    if(!rows.length){const tr=document.createElement('tr'),td=element('td','Không có dòng khớp bộ lọc.','empty-state');td.colSpan=5;tr.append(td);body.append(tr);}
  }
  function pager(data){
    const nav=root.querySelector('nav.pagination');const summary=root.querySelector('.table-summary');if(!nav)return;
    const first=data.totalItems?(data.page-1)*data.pageSize+1:0,last=Math.min(data.totalItems,(data.page-1)*data.pageSize+data.rows.length);
    summary.replaceChildren(document.createTextNode(`Hiển thị ${first}–${last} / `),element('strong',data.totalItems),document.createTextNode(' bản ghi'));
    const link=(label,page,enabled)=>{const el=element(enabled?'a':'button',label,'button button-secondary');if(enabled){const url=new URL(location.href);url.searchParams.delete('progress');url.searchParams.set('job',root.dataset.jobId);url.searchParams.set('page',page);url.searchParams.set('pageSize',data.pageSize);el.href=url.toString();if(page===data.page){el.classList.add('active');el.setAttribute('aria-current','page');}}else el.disabled=true;return el;};
    nav.replaceChildren(link('Đầu',1,data.page>1),link('Trước',data.page-1,data.page>1));
    const start=Math.max(1,Math.min(data.page-2,data.totalPages-4));for(let page=start;page<=Math.min(data.totalPages,start+4);page++)nav.append(link(page,page,true));nav.append(link('Tiếp',data.page+1,data.page<data.totalPages),link('Cuối',data.totalPages,data.page<data.totalPages));
  }
  async function poll(){
    if(stopped||!active(root.dataset.jobStatus))return;
    if(document.hidden){timer=setTimeout(poll,2000);return;}
    controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),8000);
    try{const url=new URL(location.href);url.searchParams.delete('new');url.searchParams.set('job',root.dataset.jobId);url.searchParams.set('progress','1');
      const response=await fetch(url,{credentials:'same-origin',cache:'no-store',signal:controller.signal,headers:{Accept:'application/json'}});
      if(response.status===401||response.status===403||response.redirected){stopped=true;reportError('Phiên đã hết hạn hoặc bạn không còn quyền. Đăng nhập lại để xem kết quả; không xác nhận lại tác vụ.');return;}
      if(!response.ok||!(response.headers.get('content-type')||'').includes('application/json'))throw new Error('poll');
      const data=await response.json();root.dataset.jobStatus=data.status;root.querySelector('[data-job-title]').textContent=titles[data.status]||'Trạng thái tác vụ';root.querySelector('[data-job-step]').textContent=active(data.status)?'Đang xử lý':'Đã kết thúc';
      root.querySelectorAll('[data-job-count]').forEach(el=>el.textContent=data[el.dataset.jobCount]);const processed=Number(data.success_count)+Number(data.failed_count)+Number(data.review_count);root.querySelector('[data-job-processed]').textContent=processed;root.querySelector('progress').value=processed;
      root.querySelector('[data-job-review]').hidden=!Number(data.review_count);const reason=root.querySelector('[data-job-reason]');reason.hidden=!data.reason;reason.textContent=data.reason||'';updateRows(data.rows);pager(data);reportError('');
    }catch(error){if(!stopped)reportError('Chưa tải được tiến độ. Sẽ thử lại; tác vụ trên máy chủ không bị hủy.');}
    finally{clearTimeout(timeout);if(!stopped&&active(root.dataset.jobStatus))timer=setTimeout(poll,2000);}
  }
  addEventListener('pagehide',()=>{stopped=true;clearTimeout(timer);controller?.abort();});if(active(root.dataset.jobStatus))timer=setTimeout(poll,2000);
})();
