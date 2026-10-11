(() => {
  'use strict';
  const form = document.getElementById('order-editor');
  if (!form) return;
  const context = form.dataset.context || '', optionsPath=form.dataset.optionsPath||'/api/orders/options', lines = form.querySelector('[data-order-lines]');
  const send = document.querySelector('#order-submit button'), status = document.querySelector('[data-quote-status]');
  const unitRequests = new WeakMap();
  let quoteTimer, quoteController, quoteSequence = 0, addressController, addressSequence = 0;
  const dealer = () => form.elements.dealer.value;
  const markUnsaved = () => {if(send)send.disabled=true;form.querySelector('[data-save-reminder]').textContent='Chưa lưu thay đổi. Lưu nháp trước khi gửi.';};
  // Money is a decimal string from JDBC/BigDecimal, never converted to binary floating point.
  function money(value) {
    const match=String(value ?? '0').match(/^(\d+)(?:\.(\d+))?$/);
    if(!match)return '—';const whole=match[1].replace(/\B(?=(\d{3})+(?!\d))/g,'.'),fraction=(match[2]||'').replace(/0+$/,'');
    return whole+(fraction?','+fraction:'')+' đ';
  }
  function renumber(){const rows=[...lines.querySelectorAll('[data-order-line]')];rows.forEach((row,i)=>{row.querySelector('[data-line-number]').textContent=String(i+1);row.querySelector('[data-remove-line]').disabled=rows.length===1;});form.querySelector('[data-add-line]').disabled=rows.length>=100;}
  async function units(row,preserve=false){const product=row.querySelector('[name=product]'),select=row.querySelector('[name=unit]');
    unitRequests.get(row)?.abort();const controller=new AbortController();unitRequests.set(row,controller);const selected=preserve?select.value:'';
    select.replaceChildren(new Option('Đang tải đơn vị…',''));select.disabled=true;
    if(!dealer()||!product.value){select.replaceChildren(new Option('Chọn SKU và kho phục vụ',''));select.disabled=false;return;}
    try{const response=await fetch(context+optionsPath+'?dealer='+encodeURIComponent(dealer())+'&product='+encodeURIComponent(product.value),{signal:controller.signal});if(!response.ok)throw new Error('units');const data=await response.json();if(controller.signal.aborted||unitRequests.get(row)!==controller||!row.isConnected)return;
      const items=data.items||[];select.replaceChildren(new Option(items.length?'Chọn đơn vị':'Không có đơn vị thuộc kho phục vụ',''));items.forEach(u=>select.add(new Option(u.name+' (×'+String(u.factor)+')',String(u.id))));
      if(selected&&items.some(u=>String(u.id)===selected))select.value=selected;else if(selected){select.add(new Option('Đơn vị cũ không còn hợp lệ',selected));select.value=selected;row.querySelector('[data-line-error]').textContent='Kiểm tra lại đơn vị hoặc kho phục vụ.';}
      else if(items.length===1)select.value=String(items[0].id);
    }catch(error){if(error.name!=='AbortError'){select.replaceChildren(new Option('Chưa tải được. Chọn lại SKU để thử lại.',''));row.querySelector('[data-line-error]').textContent='Không thể tải đơn vị. Hãy thử lại.';}}
    finally{if(unitRequests.get(row)===controller)select.disabled=false;}
  }
  async function addresses(){addressController?.abort();addressController=new AbortController();const controller=addressController,seq=++addressSequence,select=form.elements.address;
    select.replaceChildren(new Option('Đang tải điểm giao…',''));select.disabled=true;
    if(!dealer()){select.replaceChildren(new Option('Chọn đại lý trước',''));select.disabled=false;return;}
    try{const response=await fetch(context+optionsPath+'?dealer='+encodeURIComponent(dealer()),{signal:controller.signal});if(!response.ok)throw new Error('address');const data=await response.json();if(seq!==addressSequence)return;
      select.replaceChildren(new Option('Chọn điểm giao thuộc đại lý',''));(data.items||[]).forEach(a=>select.add(new Option(a.address+' — '+a.recipient,String(a.id))));const chosen=(data.items||[]).find(a=>a.is_default===true||a.is_default===1);if(chosen)select.value=String(chosen.id);
    }catch(error){if(error.name!=='AbortError'&&seq===addressSequence)select.replaceChildren(new Option('Chưa tải được điểm giao. Chọn lại đại lý để thử.',''));}
    finally{if(seq===addressSequence)select.disabled=false;}
  }
  function renderQuote(data){const errors=data.errors||{};const rows=[...lines.querySelectorAll('[data-order-line]')];rows.forEach((row,i)=>{const message=errors['line'+i]||errors['quantity'+i]||'',error=row.querySelector('[data-line-error]');error.textContent=message;error.id='order-line-error-'+i;error.setAttribute('role','alert');row.querySelectorAll('input:not([type=hidden]),select').forEach(input=>{input.setAttribute('aria-invalid',String(Boolean(message)));input.setAttribute('aria-describedby',error.id);});row.querySelector('[data-line-money]').textContent='';});
    (data.lines||[]).forEach(l=>{const row=rows[l.position];if(row)row.querySelector('[data-line-money]').textContent='Cơ sở: '+l.baseQuantity+' · Tiền hàng '+money(l.gross)+' · Giảm '+money(l.discount)+' · Phải thu '+money(l.net)+(l.policy?' · '+l.policy:'');});
    for(const key of ['gross','discount','net'])document.querySelector('[data-total='+key+']').textContent=money(data[key]);document.querySelector('[data-quote-warning]').textContent=data.warning||'';
    status.textContent=Object.keys(errors).length?'Chưa thể gửi: '+Object.values(errors).join(' '):'Báo giá ngày '+data.date+'. Giá và điều kiện sẽ được kiểm tra lại khi gửi.';
    if(Object.keys(errors).length&&send)send.disabled=true;
    status.classList.toggle('field-error',Object.keys(errors).length>0);
  }
  async function preview(sequence){quoteController=new AbortController();const controller=quoteController;status.textContent='Đang tính báo giá…';status.setAttribute('aria-busy','true');
    const data=new FormData(form);data.set('action','preview');
    try{const response=await fetch(form.action,{method:'POST',body:new URLSearchParams(data),signal:controller.signal,headers:{Accept:'application/json'}});if(!response.ok&&response.status!==400)throw new Error('quote');const result=await response.json();if(sequence!==quoteSequence)return;renderQuote(result);}
    catch(error){if(error.name!=='AbortError'&&sequence===quoteSequence){status.textContent='Chưa thể tính báo giá. Kiểm tra kết nối và bấm Tính lại báo giá.';status.classList.add('field-error');}}
    finally{if(sequence===quoteSequence)status.removeAttribute('aria-busy');}
  }
  function schedule(){clearTimeout(quoteTimer);quoteController?.abort();const seq=++quoteSequence;quoteTimer=setTimeout(()=>preview(seq),250);}
  form.addEventListener('input',()=>{markUnsaved();schedule();});
  form.addEventListener('change',async event=>{markUnsaved();const row=event.target.closest('[data-order-line]');if(event.target.name==='dealer'){await addresses();await Promise.all([...lines.children].map(r=>units(r,false)));}else if(event.target.name==='product'&&row)await units(row,false);schedule();});
  form.querySelector('[data-add-line]').addEventListener('click',()=>{if(lines.children.length>=100)return;const row=document.getElementById('order-line-template').content.firstElementChild.cloneNode(true);lines.append(row);window.NvdoForms.mountLookups(row);renumber();markUnsaved();form.dispatchEvent(new Event('input',{bubbles:true}));row.querySelector('.lookup-control input')?.focus();});
  lines.addEventListener('click',event=>{const button=event.target.closest('[data-remove-line]');if(!button||lines.children.length<=1)return;const row=button.closest('[data-order-line]');unitRequests.get(row)?.abort();row.remove();renumber();markUnsaved();form.dispatchEvent(new Event('input',{bubbles:true}));});
  form.querySelector('[data-refresh-quote]').addEventListener('click',schedule);
  renumber();const firstError=[...lines.children].find(r=>r.querySelector('[data-line-error]').textContent.trim());if(firstError){const field=firstError.querySelector('[name=quantity]');field.setAttribute('aria-invalid','true');field.focus();}
  Promise.all([...lines.children].filter(r=>r.querySelector('[name=product]').value).map(r=>units(r,true))).then(schedule);
})();
