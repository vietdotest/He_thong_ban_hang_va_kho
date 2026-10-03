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
  const adapt = () => { document.documentElement.classList.remove('menu-open'); if (menu) menu.inert = !desktop.matches; open?.setAttribute('aria-expanded', 'false'); };
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
    const active = path === target || (target.endsWith('/admin/users') && (path.startsWith(target + '/') || path.endsWith('/admin/assignments'))) || (target.endsWith('/catalog/products') && path.startsWith(target + '/'));
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
      if (button) { button.dataset.originalLabel = button.textContent; button.classList.add('is-submitting'); button.textContent = 'Đang xử lý…'; button.setAttribute('aria-disabled', 'true'); }
    });
  });
  addEventListener('pageshow', () => document.querySelectorAll('form[data-submitting]').forEach(form => {
    delete form.dataset.submitting; form.removeAttribute('aria-busy');
    form.querySelectorAll('[data-original-label]').forEach(button => { button.textContent = button.dataset.originalLabel; button.removeAttribute('aria-disabled'); button.classList.remove('is-submitting'); });
  }));
  document.querySelectorAll('.table-wrap,.table-scroll').forEach(el => { el.tabIndex = 0; if (!el.getAttribute('aria-label')) el.setAttribute('aria-label', 'Bảng dữ liệu'); });
})();
