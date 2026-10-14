(() => {
  'use strict';
  const form = document.getElementById('order-editor');
  if (!form) return;
  const context = form.dataset.context || '', optionsPath=form.dataset.optionsPath||'/api/orders/options', lines = form.querySelector('[data-order-lines]');
  const send = document.querySelector('#order-submit button'), status = document.querySelector('[data-quote-status]');
  const unitRequests = new WeakMap();
  let quoteTimer, quoteController, quoteSequence = 0, addressController, addressSequence = 0;
  const dealer = () => form.elements.dealer.value;
  const markUnsaved = () => {form.dataset.changed='true';if(send)send.disabled=true;form.querySelector('[data-save-reminder]').textContent='Chưa lưu thay đổi. Lưu nháp trước khi gửi.';};
  const money=value=>(typeof value==='string'?value:'—')+' đ';
  function renumber(){const rows=[...lines.querySelectorAll('[data-order-line]')];rows.forEach((row,i)=>{row.querySelector('[data-line-number]').textContent=String(i+1);row.querySelector('[data-remove-line]').disabled=rows.length===1;});form.querySelector('[data-add-line]').disabled=rows.length>=100;}
  const busy = new Set(), failures = new Set();
  const save = form.querySelector('[data-save-order]'), retry = form.querySelector('[data-retry-options]');
  const addressState=form.querySelector('[data-address-state]');
  function controls() {
    const blocked=busy.size>0||failures.size>0||!form.elements.address.value;
    save.disabled=blocked;
    retry.hidden=failures.size===0;
    if(send)send.disabled=blocked||form.dataset.changed==='true'||send.dataset.submitEligible!=='true';
  }
  function showAddressState(empty,message) {
    addressState.hidden=!empty;
    if(empty)addressState.querySelector('p').textContent=message||'Đại lý chưa có điểm giao đang sử dụng. Chưa thể lưu hoặc gửi đơn.';
    const link=form.querySelector('[data-add-address]');
    if(link){link.hidden=!empty||!dealer()||!!message;link.href=context+'/dealers/addresses?dealer='+encodeURIComponent(dealer())+'&new=1#editor';}
  }
  async function units(row,preserve=false){
    const product=row.querySelector('[name=product]'),select=row.querySelector('[name=unit]');
    unitRequests.get(row)?.abort();const controller=new AbortController();unitRequests.set(row,controller);
    const selected=preserve?select.value:'';busy.add(select);select.disabled=true;row.setAttribute('aria-busy','true');controls();
    if(!dealer()||!product.value){select.replaceChildren(new Option('Chọn SKU và kho phục vụ',''));busy.delete(select);failures.delete(select);select.disabled=false;row.removeAttribute('aria-busy');controls();return;}
    try {
      const response=await fetch(context+optionsPath+'?dealer='+encodeURIComponent(dealer())+'&product='+encodeURIComponent(product.value),{signal:controller.signal});
      if(!response.ok)throw new Error('units');const data=await response.json();
      if(controller.signal.aborted||unitRequests.get(row)!==controller||!row.isConnected)return;
      const items=data.items||[];select.replaceChildren(new Option(items.length?'Chọn đơn vị':'Không có đơn vị thuộc kho phục vụ',''));
      items.forEach(u=>select.add(new Option(u.name+' (×'+String(u.factor)+')',String(u.id))));
      if(selected&&items.some(u=>String(u.id)===selected))select.value=selected;
      else if(selected){const stale=new Option('Đơn vị cũ không còn hợp lệ',selected);stale.dataset.stale='true';select.add(stale);select.value=selected;row.querySelector('[data-line-error]').textContent='Kiểm tra lại đơn vị hoặc kho phục vụ.';}
      else if(items.length===1)select.value=String(items[0].id);
      failures.delete(select);
    } catch(error) {
      if(error.name!=='AbortError'&&unitRequests.get(row)===controller){failures.add(select);row.querySelector('[data-line-error]').textContent='Không tải được đơn vị. Dữ liệu đã nhập được giữ lại; bấm Thử tải lại tùy chọn.';}
    } finally {
      if(unitRequests.get(row)===controller){busy.delete(select);select.disabled=false;row.removeAttribute('aria-busy');controls();}
    }
  }
  async function addresses(preserve=false){
    addressController?.abort();addressController=new AbortController();const controller=addressController,seq=++addressSequence,select=form.elements.address;
    const selected=preserve?select.value:'';busy.add(select);select.disabled=true;select.setAttribute('aria-busy','true');controls();
    if(!dealer()){select.replaceChildren(new Option('Chọn đại lý trước',''));busy.delete(select);failures.delete(select);select.disabled=false;select.removeAttribute('aria-busy');showAddressState(false);controls();return;}
    try {
      const response=await fetch(context+optionsPath+'?dealer='+encodeURIComponent(dealer()),{signal:controller.signal});
      if(!response.ok)throw new Error('address');const data=await response.json();if(seq!==addressSequence)return;
      const items=data.items||[];select.replaceChildren(new Option(items.length?'Chọn điểm giao thuộc đại lý':'Chưa có điểm giao đang sử dụng',''));
      items.forEach(a=>select.add(new Option(a.address+' — '+a.recipient,String(a.id))));
      const chosen=items.find(a=>String(a.id)===selected)||items.find(a=>a.is_default===true||a.is_default===1);
      if(chosen)select.value=String(chosen.id);
      showAddressState(items.length===0);failures.delete(select);
    } catch(error) {
      if(error.name!=='AbortError'&&seq===addressSequence){failures.add(select);showAddressState(true,'Không tải được điểm giao. Dữ liệu đã nhập được giữ lại; bấm Thử tải lại tùy chọn.');}
    } finally {
      if(seq===addressSequence){busy.delete(select);select.disabled=false;select.removeAttribute('aria-busy');controls();}
    }
  }
  function renderQuote(data){const errors=data.errors||{};const rows=[...lines.querySelectorAll('[data-order-line]')];rows.forEach((row,i)=>{const message=errors['line'+i]||errors['quantity'+i]||'',error=row.querySelector('[data-line-error]');error.textContent=message;error.id='order-line-error-'+i;error.setAttribute('role','alert');row.querySelectorAll('input:not([type=hidden]),select').forEach(input=>{input.setAttribute('aria-invalid',String(Boolean(message)));input.setAttribute('aria-describedby',error.id);});row.querySelector('[data-line-money]').textContent='';});
    (data.lines||[]).forEach(l=>{const row=rows[l.position];if(row)row.querySelector('[data-line-money]').textContent='Cơ sở: '+l.baseQuantity+' · Khả dụng chung SKU: '+(l.availableBase??'—')+' đơn vị cơ sở ('+(l.availableUnits??'—')+' '+l.unit+') · Tiền hàng '+money(l.formatted?.gross)+' · Giảm '+money(l.formatted?.discount)+' · Phải thu '+money(l.formatted?.net)+(l.policy?' · '+l.policy:'');});
    const credit=data.controls?.credit,creditState=document.querySelector('[data-credit-state]');if(creditState)creditState.textContent=credit?'Còn nợ: '+credit.outstanding+' đ · Dư có: '+credit.credit+' đ · Cam kết: '+credit.commitment+' đ · Hạn mức: '+credit.limit+' đ · Hạn mức còn lại trước đơn: '+credit.remaining+' đ'+(credit.overdue?' · Có nợ quá hạn, chưa thể tiếp nhận đơn.':''):'Chọn đại lý để xem tồn và công nợ.';
    for(const key of ['gross','discount','net'])document.querySelector('[data-total='+key+']').textContent=money(data.formatted?.[key]);document.querySelector('[data-quote-warning]').textContent=(data.warning||'')+(data.needsApproval?' Đơn cần duyệt giá hoặc hạn mức trước khi tiếp nhận.':'');
    status.textContent=Object.keys(errors).length?'Chưa thể gửi: '+Object.values(errors).join(' '):'Báo giá ngày '+data.date+'. Giá và điều kiện sẽ được kiểm tra lại khi gửi.';
    if(send){send.textContent=data.needsApproval?'Gửi chờ duyệt':optionsPath.includes('/portal/')?'Gửi chờ NVKD xác nhận':'Gửi tiếp nhận';if(Object.keys(errors).length)send.disabled=true;}
    status.classList.toggle('field-error',Object.keys(errors).length>0);
  }
  async function preview(sequence){quoteController=new AbortController();const controller=quoteController;status.textContent='Đang tính báo giá…';status.setAttribute('aria-busy','true');
    const data=new FormData(form);data.set('action','preview');
    // Ô ẩn tên "action" che thuộc tính HTMLFormElement.action của trình duyệt.
    const actionUrl=form.getAttribute('action')||window.location.href;
    try{const response=await fetch(actionUrl,{method:'POST',body:new URLSearchParams(data),signal:controller.signal,headers:{Accept:'application/json'}});if(!response.ok&&response.status!==400)throw new Error('quote');const result=await response.json();if(sequence!==quoteSequence)return;renderQuote(result);}
    catch(error){if(error.name!=='AbortError'&&sequence===quoteSequence){status.textContent='Chưa thể tính báo giá. Kiểm tra kết nối và bấm Tính lại báo giá.';status.classList.add('field-error');}}
    finally{if(sequence===quoteSequence)status.removeAttribute('aria-busy');}
  }
  function schedule(){controls();clearTimeout(quoteTimer);quoteController?.abort();const seq=++quoteSequence;if(!form.elements.address.value||busy.size||failures.size){status.textContent='Chọn điểm giao và tải đủ tùy chọn để tính báo giá.';return;}quoteTimer=setTimeout(()=>preview(seq),250);}
  form.addEventListener('input',()=>{markUnsaved();schedule();});
  form.addEventListener('change',async event=>{markUnsaved();const row=event.target.closest('[data-order-line]');if(event.target.name==='dealer'){await addresses(false);await Promise.all([...lines.children].map(r=>units(r,false)));}else if(event.target.name==='product'&&row)await units(row,false);schedule();});
  form.querySelector('[data-add-line]').addEventListener('click',()=>{if(lines.children.length>=100)return;const row=document.getElementById('order-line-template').content.firstElementChild.cloneNode(true);lines.append(row);window.NvdoForms.mountLookups(row);renumber();markUnsaved();form.dispatchEvent(new Event('input',{bubbles:true}));row.querySelector('.lookup-control input')?.focus();});
  lines.addEventListener('click',event=>{const button=event.target.closest('[data-remove-line]');if(!button||lines.children.length<=1)return;const row=button.closest('[data-order-line]');unitRequests.get(row)?.abort();for(const select of row.querySelectorAll('select')){busy.delete(select);failures.delete(select);}row.remove();renumber();markUnsaved();form.dispatchEvent(new Event('input',{bubbles:true}));});
  form.querySelector('[data-refresh-quote]').addEventListener('click',schedule);
  retry.addEventListener('click',async()=>{await addresses(true);await Promise.all([...lines.children].map(row=>units(row,true)));schedule();});
  form.addEventListener('submit',event=>{if(busy.size||failures.size||!form.elements.address.value){event.preventDefault();controls();showAddressState(true,failures.size?'Không tải được tùy chọn. Hãy thử lại trước khi lưu.':'Chọn điểm giao hợp lệ trước khi lưu đơn.');}});
  form.querySelector('[data-order-controls]').disabled=false;
  controls();
  renumber();const firstError=[...lines.children].find(r=>r.querySelector('[data-line-error]').textContent.trim());if(firstError){const field=firstError.querySelector('[name=quantity]');field.setAttribute('aria-invalid','true');field.focus();}
  const initialAddresses=dealer()&&!form.elements.address.value?addresses(true):Promise.resolve();
  initialAddresses.then(()=>Promise.all([...lines.children].filter(r=>r.querySelector('[name=product]').value).map(r=>units(r,true)))).then(schedule);
})();
