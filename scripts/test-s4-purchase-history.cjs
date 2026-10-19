// S4-04: Chạy trên Tomcat và dữ liệu thử cô lập; không dùng tài khoản hoặc database đang vận hành.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');

async function main() {
  const fixture = JSON.parse(fs.readFileSync(process.env.S4_FIXTURE || 'target/s4-04-browser-fixture.json', 'utf8'));
  const base = new URL(fixture.base);
  assert.equal(base.hostname, '127.0.0.1');
  assert.equal(base.port, '18096');
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 1366, height: 1000}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(fixture.base + '/login');
    await page.locator('[name=identity]').fill(fixture.username);
    await page.locator('[name=password]').fill(process.env.S4_QA_PASSWORD || 'admin123');
    await Promise.all([page.waitForURL('**/dashboard'), page.locator('button[type=submit]').click()]);
    const response = await page.request.get(fixture.base + '/api/orders/purchase-history?dealer=' + fixture.dealer);
    assert.equal(response.status(), 200);
    const history = await response.json();
    assert.equal(history.items.length, 2);
    assert.equal(history.previousItems.length, 2);
    assert.ok(!JSON.stringify(history).includes('cost_price'));
    await page.goto(fixture.base + '/orders?action=new&dealer=' + fixture.dealer);
    await page.locator('[data-history-add]:enabled').waitFor();
    assert.equal(await page.locator('[data-history-rows] tr').count(), 2);
    await page.locator('[data-history-add]').click();
    await page.waitForFunction(() => document.querySelector('[data-history-status]').textContent.includes('Đã thêm 2 SKU'));
    assert.equal(await page.locator('[data-order-line]').count(), 2);
    const quantities = await page.locator('[name=quantity]').evaluateAll(inputs => inputs.map(input => input.value).sort());
    assert.deepEqual(quantities, ['2', '3']);
    await page.locator('[data-history-add]').click();
    await page.waitForFunction(() => document.querySelector('[data-history-status]').textContent.includes('Đã thêm 0 SKU'));
    assert.equal(await page.locator('[data-order-line]').count(), 2);
    await page.locator('[data-save-order]:enabled').waitFor();
    await Promise.all([page.waitForURL(/notice=saved/), page.locator('[data-save-order]').click()]);
    await page.waitForFunction(() => document.querySelector('[data-quote-status]').textContent.includes('Báo giá ngày'));
    assert.equal(await page.locator('[data-order-line]').count(), 2);
    assert.equal(errors.length, 0, errors.join('\n'));
    await page.screenshot({path: 'target/s4-04-purchase-history.png', fullPage: true});
    console.log('PASS: lịch sử hai SKU, thêm nhóm, giữ dòng trùng, lưu nháp và tính lại báo giá; không có lỗi JavaScript.');

    await page.route('**/api/orders/purchase-history?*', async route => {
      const data = structuredClone(history);
      data.previousItems[0].available = false;
      data.previousItems[0].notice = 'Sản phẩm đã ngừng bán.';
      await route.fulfill({json: data});
    });
    await page.goto(fixture.base + '/orders?action=new&dealer=' + fixture.dealer);
    await page.locator('[data-history-add]:enabled').waitFor();
    await page.locator('[data-history-add]').click();
    await page.waitForFunction(() => document.querySelector('[data-history-status]').textContent.includes('Đã thêm 1 SKU'));
    assert.ok((await page.locator('[data-history-status]').textContent()).includes('ngừng bán'));
    await page.unroute('**/api/orders/purchase-history?*');

    page.on('dialog', dialog => dialog.accept());
    await page.goto(fixture.base + '/orders?action=new&dealer=' + fixture.dealer);
    await page.locator('[data-history-add]:enabled').waitFor();
    await page.evaluate(() => {
      const lines = document.querySelector('[data-order-lines]');
      const template = document.getElementById('order-line-template');
      while (lines.children.length < 100) lines.append(template.content.firstElementChild.cloneNode(true));
      for (const row of lines.children) row.querySelector('[name=quantity]').value = '7';
    });
    await page.locator('[data-history-add]').click();
    await page.waitForFunction(() => document.querySelector('[data-history-status]').textContent.includes('Đã thêm 0 SKU'));
    assert.equal(await page.locator('[data-order-line]').count(), 100);
    assert.ok((await page.locator('[data-history-status]').textContent()).includes('100 dòng'));
    await page.evaluate(() => {
      const dealer = document.querySelector('[name=dealer]');
      dealer.value = '';
      dealer.dispatchEvent(new Event('change', {bubbles: true}));
    });
    await page.waitForFunction(() => document.querySelector('[data-history-status]').textContent.includes('Chọn đại lý'));
    assert.equal(await page.locator('[data-history-rows] tr').count(), 0);
    console.log('PASS: bỏ qua hàng ngừng bán, giữ giới hạn 100 dòng và xóa lịch sử khi bỏ chọn đại lý.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
