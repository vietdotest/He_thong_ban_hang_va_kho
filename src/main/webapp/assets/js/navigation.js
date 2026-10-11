(() => {
  const menu = document.getElementById('nvdo-menu');
  const open = document.querySelector('[data-menu-open]');
  const close = document.querySelector('[data-menu-close]');
  const desktop = matchMedia('(min-width: 1024px)');
  const setMenu = value => {
    document.documentElement.classList.toggle('menu-open', value);
    open?.setAttribute('aria-expanded', String(value));
    if (!desktop.matches && menu) {
      menu.inert = !value;
      menu.setAttribute('aria-modal', String(value));
      if (value) { menu.setAttribute('role', 'dialog'); close?.focus(); }
      else { menu.removeAttribute('role'); menu.removeAttribute('aria-modal'); open?.focus(); }
    }
  };
  open?.addEventListener('click', () => setMenu(true));
  close?.addEventListener('click', () => setMenu(false));
  const adapt = () => {
    document.documentElement.classList.remove('menu-open');
    if (menu) { menu.inert = !desktop.matches; menu.removeAttribute('role'); menu.removeAttribute('aria-modal'); }
    open?.setAttribute('aria-expanded', 'false');
  };
  desktop.addEventListener('change', adapt); adapt();
  document.addEventListener('keydown', event => {
    if (!document.documentElement.classList.contains('menu-open')) return;
    if (event.key === 'Escape') { event.preventDefault(); setMenu(false); }
    if (event.key === 'Tab') {
      const items = [...menu.querySelectorAll('a[href],button,input')].filter(el => !el.disabled && el.getClientRects().length);
      const first = items[0], last = items.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });
  const path = location.pathname;
  document.querySelectorAll('.sidebar-nav a, .sidebar-user a').forEach(link => {
    const target = new URL(link.href).pathname;
    const active = path === target || (target.endsWith('/admin/users') && (path.startsWith(target + '/') || path.endsWith('/admin/assignments'))) || (target.endsWith('/catalog/products') && path.startsWith(target + '/')) || (target.endsWith('/dealers') && path.startsWith(target + '/'));
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current', 'page');
  });
  document.querySelectorAll('form').forEach(form => {
    if ((form.method || '').toLowerCase() !== 'post' || form.id === 'login-form') return;
    form.addEventListener('submit', event => {
      if (form.dataset.submitting) { event.preventDefault(); return; }
      if (!form.noValidate && !form.checkValidity()) return;
      if (form.dataset.confirm && !confirm(form.dataset.confirm)) { event.preventDefault(); return; }
      form.dataset.submitting = 'true';
      form.setAttribute('aria-busy', 'true');
      const button = event.submitter;
      if (button) { button.dataset.originalHtml = button.innerHTML; button.classList.add('is-submitting'); button.textContent = 'Đang xử lý…'; button.setAttribute('aria-disabled', 'true'); }
    });
  });
  addEventListener('pageshow', () => document.querySelectorAll('form[data-submitting]').forEach(form => {
    delete form.dataset.submitting; form.removeAttribute('aria-busy');
    form.querySelectorAll('[data-original-html]').forEach(button => { button.innerHTML = button.dataset.originalHtml; delete button.dataset.originalHtml; button.removeAttribute('aria-disabled'); button.classList.remove('is-submitting'); });
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
    if (select) select.disabled = !compact.matches;
    if (hidden) hidden.disabled = compact.matches;
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
