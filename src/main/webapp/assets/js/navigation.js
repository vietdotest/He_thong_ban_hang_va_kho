(() => {
  'use strict';
  document.documentElement.classList.add('js-ready');
  const shell = document.getElementById('navigation-shell'), menu = document.getElementById('nvdo-menu');
  const open = document.querySelector('[data-menu-open]'), close = document.querySelector('[data-menu-close]');
  const desktop = matchMedia('(min-width: 1024px)');
  let restoreMenuFocus;
  const setMenu = value => {
    if (!shell || !menu) return;
    if (value && !desktop.matches) restoreMenuFocus = document.activeElement;
    shell.open = desktop.matches || value;
    document.documentElement.classList.toggle('menu-open', value && !desktop.matches);
    open?.setAttribute('aria-expanded', String(value));
    menu.inert = !desktop.matches && !value;
    if (value && !desktop.matches) {menu.setAttribute('role','dialog');menu.setAttribute('aria-modal','true');close?.focus();}
    else {menu.removeAttribute('role');menu.removeAttribute('aria-modal');if (!desktop.matches) restoreMenuFocus?.focus();}
  };
  open?.addEventListener('click', () => setMenu(true));close?.addEventListener('click', () => setMenu(false));
  desktop.addEventListener('change', () => setMenu(false));setMenu(false);
  document.addEventListener('keydown', event => {
    if (!document.documentElement.classList.contains('menu-open')) return;
    if (event.key === 'Escape') {event.preventDefault();setMenu(false);}
    if (event.key === 'Tab') {
      const items=[...menu.querySelectorAll('a[href],button,input,summary')].filter(el=>!el.disabled&&el.getClientRects().length);
      const first=items[0],last=items.at(-1);
      if (event.shiftKey&&document.activeElement===first) {event.preventDefault();last?.focus();}
      else if (!event.shiftKey&&document.activeElement===last) {event.preventDefault();first?.focus();}
    }
  });
  document.querySelectorAll('.account-menu,.role-menu').forEach(disclosure=>{
    disclosure.addEventListener('toggle',()=>{if(disclosure.open)document.querySelectorAll('.account-menu,.role-menu').forEach(other=>{if(other!==disclosure)other.open=false;});});
    disclosure.addEventListener('keydown',event=>{if(event.key==='Escape'){event.stopPropagation();event.preventDefault();disclosure.open=false;disclosure.querySelector('summary').focus();}});
  });
  document.addEventListener('click',event=>document.querySelectorAll('.account-menu[open],.role-menu[open]').forEach(d=>{if(!d.contains(event.target))d.open=false;}));
  const path=location.pathname;
  document.querySelectorAll('.sidebar-nav a,.sidebar-user a').forEach(link=>{
    const target=new URL(link.href).pathname;
    const active=path===target||(target.endsWith('/admin/users')&&(path.startsWith(target+'/')||path.endsWith('/admin/assignments')))||(target.endsWith('/catalog/products')&&path.startsWith(target+'/'))||(target.endsWith('/dealers')&&path.startsWith(target+'/'));
    link.classList.toggle('active',active);if(active)link.setAttribute('aria-current','page');
  });
  const dialog=document.createElement('dialog');dialog.className='ui-confirm-dialog';
  dialog.setAttribute('aria-labelledby','ui-confirm-title');dialog.setAttribute('aria-describedby','ui-confirm-message');
  dialog.innerHTML='<h2 id="ui-confirm-title">Xác nhận thao tác</h2><p id="ui-confirm-message"></p><div class="form-actions"><button class="button button-secondary" type="button" data-cancel>Hủy</button><button class="button button-primary" type="button" data-accept>Xác nhận</button></div>';
  document.body.append(dialog);let pending;
  dialog.querySelector('[data-cancel]').addEventListener('click',()=>dialog.close());
  dialog.addEventListener('close',()=>{const previous=pending;pending=null;previous?.submitter?.focus();});
  dialog.querySelector('[data-accept]').addEventListener('click',()=>{
    const item=pending;if(!item)return;
    dialog.close();item.form.dataset.confirmed='true';item.form.requestSubmit(item.submitter);
  });
  document.querySelectorAll('.confirm-disclosure').forEach(d=>d.open=true);
  document.querySelectorAll('form').forEach(form=>{
    if(form.method.toLowerCase()!=='post'||form.id==='login-form')return;
    form.addEventListener('submit',event=>{
      if(event.defaultPrevented)return;
      if(form.dataset.submitting){event.preventDefault();return;}
      if(!form.noValidate&&!form.checkValidity())return;
      if(form.dataset.confirm&&!form.dataset.confirmed){
        event.preventDefault();pending={form,submitter:event.submitter};
        dialog.querySelector('[data-accept]').textContent=event.submitter?.textContent.trim()||'Xác nhận';
        dialog.querySelector('#ui-confirm-message').textContent=form.dataset.confirm;
        dialog.showModal();dialog.querySelector('[data-cancel]').focus();return;
      }
      delete form.dataset.confirmed;
      queueMicrotask(()=>{
        if(event.defaultPrevented)return;
        form.dataset.submitting='true';form.setAttribute('aria-busy','true');const button=event.submitter;
        if(button){button.dataset.originalHtml=button.innerHTML;button.classList.add('is-submitting');button.textContent='Đang xử lý…';button.setAttribute('aria-disabled','true');}
      });
    });
  });
  addEventListener('pageshow',()=>document.querySelectorAll('form[data-submitting]').forEach(form=>{
    delete form.dataset.submitting;form.removeAttribute('aria-busy');
    form.querySelectorAll('[data-original-html]').forEach(button=>{button.innerHTML=button.dataset.originalHtml;delete button.dataset.originalHtml;button.removeAttribute('aria-disabled');button.classList.remove('is-submitting');});
  }));
  document.querySelectorAll('.table-wrap,.table-scroll').forEach(el => { el.tabIndex = 0; if (!el.getAttribute('aria-label')) el.setAttribute('aria-label', 'Bảng dữ liệu'); });
  document.querySelectorAll('.file-input').forEach(input => input.addEventListener('change', () => {
    const helper = document.getElementById('avatar-helper');
    if (helper && input.files[0]) helper.textContent = 'Đã chọn: ' + input.files[0].name + '. Ảnh sẽ được lưu cùng thông tin.';
  }));
  document.querySelectorAll('[data-editor]').forEach(button => button.addEventListener('click', event => {
    const editor = document.getElementById(button.dataset.editor);
    if (!editor) return;
    if (button.dataset.scopeKind) {
      if (Number(editor.querySelector('[name=id]')?.value) > 0) return;
      editor.querySelector('[name=kind]').value = button.dataset.scopeKind;
    } else if (editor.open) return;
    event.preventDefault(); editor.open = true;
    editor.scrollIntoView({block: 'start', behavior: 'smooth'});
    editor.querySelector('input:not([type=hidden]),select,textarea')?.focus({preventScroll:true});
  }));
  const compact = matchMedia('(max-width: 767px)');
  const adaptProductFilter = () => {
    const select = document.querySelector('.mobile-category-filter select');
    const hidden = document.querySelector('.desktop-category-value');
    if (select) select.disabled = false;
    if (hidden) hidden.disabled = true;
  };
  compact.addEventListener('change', adaptProductFilter); adaptProductFilter();
  document.querySelectorAll('.field-error').forEach((error,index) => {
    if (!error.textContent.trim()) return;
    error.setAttribute('role','alert');
    const field = error.closest('label,.form-field')?.querySelector('input,select,textarea');
    if (field) {
      error.id ||= 'field-error-' + index;
      field.setAttribute('aria-invalid','true');
      field.setAttribute('aria-describedby',error.id);
    }
  });
  const importFile = document.querySelector('[data-import-file]');
  if (importFile) {
    const adaptFile = () => {
      document.querySelector('[data-preview-button]').disabled = !importFile.files.length;
      if (importFile.files.length) document.querySelector('[data-file-name]').textContent = 'Đã chọn: ' + importFile.files[0].name;
    };
    importFile.addEventListener('change', adaptFile); adaptFile();
  }
  const importStatus = document.querySelector('[data-import-status]');
  if (importStatus) {
    const filterRows = () => {
      const rows = [...document.querySelectorAll('[data-import-row]')];
      rows.forEach(row => row.hidden = importStatus.value !== 'all' && row.dataset.importRow !== importStatus.value);
      document.querySelector('[data-import-empty]').hidden = rows.some(row => !row.hidden);
    };
    importStatus.addEventListener('change', filterRows); filterRows();
  }
})();
