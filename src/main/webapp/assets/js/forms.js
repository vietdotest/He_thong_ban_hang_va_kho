(() => {
  'use strict';
  const context = document.currentScript?.dataset.appContext || '';
  let counter = 0;
  function attachLookup(element, type) {
    if(element.dataset.lookupAttached)return;
    element.dataset.lookupAttached='true';
    const select = element.tagName === 'SELECT' ? element : null;
    const optional = select && !select.required;
    const wrapper = document.createElement('div'); wrapper.className = 'lookup-control';
    element.before(wrapper); wrapper.append(element);
    const input = select ? document.createElement('input') : element;
    if (select) {
      select.hidden = true; select.removeAttribute('required');
      input.type = 'search'; input.placeholder = 'Gõ mã hoặc tên để tìm';
      input.value = select.value ? select.selectedOptions[0]?.textContent.trim() || '' : '';
      wrapper.prepend(input);
    }
    const menu = document.createElement('div'); menu.className = 'lookup-menu'; menu.hidden = true;
    menu.id = 'lookup-' + (++counter); menu.setAttribute('role', 'listbox'); wrapper.append(menu);
    input.setAttribute('role', 'combobox'); input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-controls', menu.id); input.setAttribute('aria-expanded', 'false'); input.autocomplete = 'off';
    let timer, controller, sequence = 0, active = -1, options = [];
    const close = () => { menu.hidden = true; input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); active = -1; };
    const open = () => { menu.hidden = false; input.setAttribute('aria-expanded', 'true'); };
    const status = message => { menu.replaceChildren(); const p = document.createElement('p'); p.className = 'lookup-status'; p.textContent = message; p.setAttribute('role', 'status'); menu.append(p); open(); };
    const choose = row => {
      input.setCustomValidity('');
      if (select) { select.replaceChildren(new Option(row.code + ' — ' + row.name, String(row.id), true, true)); input.value = select.options[0].textContent; select.dispatchEvent(new Event('change', {bubbles:true})); }
      else input.value = row.code;
      close(); input.dispatchEvent(new Event('lookupselect', {bubbles:true}));
    };
    input.addEventListener('input', () => {
      clearTimeout(timer); controller?.abort(); const request = ++sequence; close();
      if (select) { select.value = ''; input.setCustomValidity('Hãy chọn một kết quả trong danh sách.'); }
      const q = input.value.trim(); if(optional && q.length===0)input.setCustomValidity(''); if(q.length < 2) return;
      timer = setTimeout(async () => {
        controller = new AbortController(); status('Đang tìm…');
        try {
          const response = await fetch(context + '/api/lookups/' + type + '?q=' + encodeURIComponent(q), {signal:controller.signal, headers:{Accept:'application/json'}});
          if(!response.ok) throw new Error('lookup');
          const data = await response.json(); if(request !== sequence) return;
          options = data.items || []; menu.replaceChildren(); active = -1;
          if(!options.length) {status('Không có kết quả phù hợp. Thử từ khóa khác.'); return;}
          options.forEach((row, index) => {
            const item = document.createElement('button'); item.type='button'; item.className='lookup-option'; item.id=menu.id+'-'+index; item.setAttribute('role','option'); item.setAttribute('aria-selected','false');
            const name = document.createElement('span'); name.textContent=row.name;
            const code = document.createElement('small'); code.textContent=row.code; item.append(name,code);
            item.addEventListener('mousedown', e => e.preventDefault()); item.addEventListener('click',()=>choose(row)); menu.append(item);
          }); open();
        } catch(error) {if(error.name !== 'AbortError' && request===sequence) status('Chưa thể tải gợi ý. Hãy thử lại hoặc dùng nút tìm kiếm.');}
      },250);
    });
    input.addEventListener('keydown', e => {
      if(e.key==='Escape' || e.key==='Tab') {close();return;}
      if(menu.hidden) return;
      if(e.key==='ArrowDown' || e.key==='ArrowUp') {
        const buttons=[...menu.querySelectorAll('[role=option]')]; if(!buttons.length)return;
        e.preventDefault(); active=(active+(e.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length;
        buttons.forEach((b,i)=>b.setAttribute('aria-selected',String(i===active))); input.setAttribute('aria-activedescendant',buttons[active].id); buttons[active].scrollIntoView({block:'nearest'});
      } else if(e.key==='Enter' && active>=0) {e.preventDefault();choose(options[active]);}
    });
    document.addEventListener('pointerdown',e=>{if(!wrapper.contains(e.target))close();});
  }
  document.querySelectorAll('input[name=q]:not([type=hidden])').forEach(input=>{
    if(location.pathname.endsWith('/catalog/products') || location.pathname.endsWith('/catalog/units')) attachLookup(input,'products');
    if(location.pathname.endsWith('/admin/users')) attachLookup(input,'users');
    if(location.pathname.endsWith('/dealers')) attachLookup(input,'dealers');
  });
  document.querySelectorAll('select[name=product]').forEach(select=>attachLookup(select,'products'));
  document.querySelectorAll('select[data-lookup]').forEach(select=>attachLookup(select,select.dataset.lookup));
  document.querySelectorAll('form[method=post]').forEach(form=>{
    let dirty = false;
    form.addEventListener('input',()=>{dirty=true;});
    form.addEventListener('change',()=>{dirty=true;});
    form.addEventListener('submit',event=>{if(!event.defaultPrevented)dirty=false;});
    addEventListener('beforeunload',event=>{if(dirty&&!form.dataset.submitting){event.preventDefault();event.returnValue='';}});
    const first = form.querySelector('[aria-invalid=true]'); if(first) first.focus();
  });
})();
