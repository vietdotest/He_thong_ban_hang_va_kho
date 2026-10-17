document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-print]').forEach(button => button.addEventListener('click', () => window.print()));
  const form = document.querySelector('[data-mvp-receipt]');
  if (!form) return;
  const lines = form.querySelector('[data-receipt-lines]');
  const warehouse = form.elements.warehouse;
  const unitOptions = Array.from(lines.querySelector('[name=unit]').options).map(option => option.cloneNode(true));
  const filter = () => {
    for (const option of form.elements.supplier.options) {
      if (!option.value) continue;
      option.disabled = option.hidden = option.dataset.warehouse !== warehouse.value;
    }
    if (form.elements.supplier.selectedOptions[0]?.disabled) form.elements.supplier.value = '';
    for (const row of lines.querySelectorAll('[data-receipt-line]')) {
      const product = row.querySelector('[name=product]').value;
      const unit = row.querySelector('[name=unit]');
      const selected = unit.value;
      unit.replaceChildren(...unitOptions.filter(option => !option.value ||
        (option.dataset.product === product && (!option.dataset.warehouse || option.dataset.warehouse === warehouse.value)))
        .map(option => option.cloneNode(true)));
      unit.value = Array.from(unit.options).some(option => option.value === selected) ? selected : '';
    }
  };
  form.addEventListener('change', filter);
  form.querySelector('[data-add-line]').addEventListener('click', () => {
    if (lines.children.length >= 100) return;
    const row = lines.firstElementChild.cloneNode(true);
    row.querySelectorAll('input,select').forEach(input => { input.value = input.name === 'quantity' ? '1' : ''; });
    lines.append(row); filter();
  });
  lines.addEventListener('click', event => {
    if (event.target.closest('[data-remove-line]') && lines.children.length > 1)
      event.target.closest('[data-receipt-line]').remove();
  });
  filter();
});
