const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.S201_URL || 'http://127.0.0.1:18083';
if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base)) throw Error('Only a local preview URL is allowed.');
const root = path.resolve(__dirname, '..');
const evidence = path.join(root, '.tools', 's201-evidence');
fs.mkdirSync(evidence, { recursive: true });
const checks = [];
function check(label, value) {
  if (!value) throw Error(label);
  checks.push(label);
  console.log('PASS ' + label);
}
function db(sql) {
  return execFileSync('docker', ['exec', '-i', '-e', 'MYSQL_PWD=sales_app123', 'codegym-s201-preview',
    'mysql', '-usales_app', '--default-character-set=utf8mb4', '-B', '-N', 'sales_inventory'],
    { input: sql, encoding: 'utf8' }).trim();
}
function workbook(rows) {
  return execFileSync(process.env.JAVA_EXE || 'C:/Program Files/Java/jdk-17/bin/java.exe',
    ['-Dfile.encoding=UTF-8', '-cp', path.join(root, 'target/classes'), path.join(__dirname, 'ImportWorkbookFixture.java')],
    { input: rows.map(row => row.join('\t')).join('\n') + '\n' });
}
const roles = ['ADMIN', 'SALES_MANAGER', 'SALES', 'WAREHOUSE_MANAGER', 'WAREHOUSE', 'ACCOUNTANT', 'DIRECTOR'];
function seed() {
  for (const role of [...roles, 'MULTI', 'REVOKED']) {
    const name = 's201-' + role.toLowerCase();
    db("INSERT IGNORE INTO users(username,username_normalized,email,email_normalized,full_name,password_hash,status) " +
      "SELECT '" + name + "','" + name + "','" + name + "@test.local','" + name + "@test.local','Người thử " + role +
      "',password_hash,'ACTIVE' FROM users WHERE username='admin'; " +
      "UPDATE users SET status='ACTIVE',must_change_password=0 WHERE username='" + name + "'; " +
      "DELETE ur FROM user_roles ur JOIN users u ON u.id=ur.user_id WHERE u.username='" + name + "';");
    const assigned = role === 'MULTI' ? ['ADMIN', 'SALES'] : role === 'REVOKED' ? ['ADMIN'] : [role];
    for (const item of assigned) db("INSERT INTO user_roles(user_id,role_id) SELECT u.id,r.id FROM users u,roles r WHERE u.username='" + name + "' AND r.code='" + item + "'");
  }
  db("INSERT IGNORE INTO warehouses(code,name) VALUES('S201-KHO','Kho nghiệm thu'); INSERT IGNORE INTO territories(code,name) VALUES('S201-DIA-BAN','Địa bàn nghiệm thu');");
}
(async () => {
  seed();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1366, height: 1000 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  async function login(name) {
    await context.clearCookies();
    await page.goto(base + '/login');
    await page.locator('[name=identity]').fill(name);
    await page.locator('[name=password]').fill('admin123');
    await Promise.all([page.waitForURL('**/dashboard'), page.getByRole('button', { name: 'Đăng nhập', exact: true }).click()]);
  }
  async function importPage() { return page.goto(base + '/admin/users/import'); }
  async function token() { return page.locator('.import-upload-form [name=_csrf]').inputValue(); }
  async function upload(bytes, csrf) {
    if (csrf === undefined) csrf = await token();
    return context.request.post(base + '/admin/users/import', { multipart: {
      _csrf: csrf, file: { name: 'nghiem-thu.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: bytes }
    }, maxRedirects: 0 });
  }
  async function previewTokenFrom(response) {
    const html = await response.text();
    return page.evaluate(html => new DOMParser().parseFromString(html, 'text/html').querySelector('.import-confirm [name=token]')?.value, html);
  }
  try {
    const guest = await browser.newContext();
    let response = await guest.request.get(base + '/admin/users/import', { maxRedirects: 0 });
    check('Guest GET redirects to login', response.status() === 303 && response.headers().location.includes('/login'));
    response = await guest.request.post(base + '/admin/users/import', { form: { action: 'confirm' }, maxRedirects: 0 });
    check('Guest POST redirects to login', response.status() === 303);
    await guest.close();
    for (const role of roles.filter(role => role !== 'ADMIN')) {
      await login('s201-' + role.toLowerCase());
      response = await context.request.get(base + '/admin/users/import?template=1', { maxRedirects: 0 });
      check(role + ' cannot download or access import', response.status() === 403);
      response = await context.request.post(base + '/admin/users/import', { form: { action: 'confirm' }, maxRedirects: 0 });
      check(role + ' cannot confirm import', response.status() === 403);
    }
    await login('s201-multi');
    check('ADMIN plus SALES can access import', (await importPage()).status() === 200);
    const actorStamp = String(Date.now()).slice(-8);
    const actorName = 'actor-' + actorStamp;
    const actorBytes = workbook([[actorName, actorName + '@test.local', 'Kiểm tra người nhập', '03' + actorStamp, 'SALES', '', '']]);
    response = await upload(actorBytes);
    const actorPreview = await previewTokenFrom(response);
    response = await context.request.post(base + '/admin/users/import?userId=1&actorId=1', {
      form: { _csrf: await token(), action: 'confirm', token: actorPreview, userId: '1', actorId: '1', username: 'admin', role: 'ADMIN' }, maxRedirects: 0
    });
    const actorId = db("SELECT id FROM users WHERE username='s201-multi'");
    check('Forged actor parameters cannot change audit identity', response.status() === 200 &&
      db("SELECT actor_user_id FROM audit_logs a JOIN users u ON u.id=a.object_id WHERE u.username='" + actorName + "' AND a.event_type='USER_CREATED'") === actorId);
    check('Forged account fields cannot replace preview data', db("SELECT COUNT(*) FROM users WHERE username='" + actorName + "' AND status='PENDING_ACTIVATION'") === '1');
    await login('admin');
    check('Import JSP compiles on Tomcat', (await importPage()).status() === 200);
    response = await context.request.get(base + '/catalog/products?page=0', { maxRedirects: 0 });
    check('Other PortalServlet routes retain their default 400 handling', response.status() === 400 && (await response.text()).includes('Trang không hợp lệ.'));
    response = await context.request.get(base + '/admin/users/import?template=1');
    const template = await response.body();
    check('Template download has XLSX content and filename', response.status() === 200 && template.subarray(0,2).toString() === 'PK' &&
      response.headers()['content-disposition'].includes('nguoi-dung.xlsx'));
    for (const form of [{ action: 'confirm' }, { action: 'confirm', _csrf: 'bad' }]) {
      response = await context.request.post(base + '/admin/users/import', { form, maxRedirects: 0 });
      check('Missing or invalid CSRF is rejected', response.status() === 403);
    }
    response = await upload(Buffer.from('not an Excel workbook'));
    check('Malformed XLSX returns inline 400', response.status() === 400 && (await response.text()).includes('import-file-error'));
    response = await upload(workbook([]));
    check('Empty workbook returns inline 400', response.status() === 400 && (await response.text()).includes('không có dòng dữ liệu'));
    response = await upload(workbook([['short', 'email']]));
    check('Short invalid row renders without JSP failure', response.status() === 200 && (await response.text()).includes('Số điện thoại'));
    response = await upload(Buffer.alloc(10 * 1024 * 1024 + 1));
    check('File exceeding 10 MiB returns inline 400', response.status() === 400 && (await response.text()).includes('import-file-error'));
    response = await upload(Buffer.alloc(12 * 1024 * 1024));
    check('Request exceeding multipart limit returns inline 400', response.status() === 400 && (await response.text()).includes('import-file-error'));
    const stamp = String(Date.now()).slice(-8);
    const first = 'ui-' + stamp + '-a', second = 'ui-' + stamp + '-b';
    const phoneA = '09' + stamp, phoneB = '08' + stamp;
    const html = '<img src=x onerror="window.importInjected=true">';
    const rows = [
      [first, first + '@test.local', html, phoneA, 'WAREHOUSE,SALES', 'S201-KHO', 'S201-DIA-BAN'],
      ['ui-' + stamp + '-bad', 'bad-' + stamp + '@test.local', 'Tên sai số', 'bad', 'SALES', '', ''],
      [second, first + '@test.local', 'Dòng trùng', phoneB, 'SALES', '', ''],
      [second, second + '@test.local', "Nguyễn O'An", '+84' + phoneB.substring(1), 'ACCOUNTANT', '', '']
    ];
    const bytes = workbook(rows);
    fs.writeFileSync(path.join(evidence, 'mixed.xlsx'), bytes);
    await page.locator('[name=file]').setInputFiles({ name: 'mixed.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: bytes });
    await page.locator('[name=file]').focus();
    await page.keyboard.press('Tab');
    check('Keyboard reaches preview button', await page.getByRole('button', { name: 'Xem trước', exact: true }).evaluate(e => e === document.activeElement));
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/admin/users/import') && r.request().method() === 'POST'), page.keyboard.press('Enter')]);
    check('Preview shows two valid and two invalid rows', (await page.locator('p.import-summary').innerText()).includes('Hợp lệ: 2. Có lỗi: 2.'));
    check('Invalid duplicate row does not reserve next valid row', await page.locator('tbody tr.import-row-valid').count() === 2);
    check('HTML in preview is escaped', await page.locator('tbody').innerText().then(t => t.includes(html)) && await page.evaluate(() => window.importInjected !== true) && await page.locator('tbody img').count() === 0);
    check('Desktop has no horizontal page overflow', await page.evaluate(() => document.documentElement.scrollWidth <= 1366));
    await page.screenshot({ path: path.join(evidence, 'preview-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 360, height: 800 });
    check('360px has no horizontal page overflow', await page.evaluate(() => document.documentElement.scrollWidth <= 360));
    check('Wide table scrolls inside its region', await page.locator('.import-table').evaluate(e => e.scrollWidth > e.clientWidth && e.getBoundingClientRect().right <= 360));
    await page.screenshot({ path: path.join(evidence, 'preview-mobile.png'), fullPage: true });
    const previewToken = await page.locator('.import-confirm [name=token]').inputValue();
    const csrf = await token();
    check('Preview creates no account before confirmation', db("SELECT COUNT(*) FROM users WHERE username IN ('" + first + "','" + second + "')") === '0');
    await page.locator('.import-confirm button').focus();
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/admin/users/import') && r.request().method() === 'POST'), page.keyboard.press('Enter')]);
    check('Keyboard confirmation shows accurate totals', (await page.getByRole('status').innerText()).includes('Đã nhập: 2. Không nhập: 2.'));
    check('Imported rows are marked as imported, not merely valid', await page.locator('tbody tr.import-row-valid td:last-child').allTextContents().then(values => values.every(v => v.trim() === 'Đã nhập')));
    check('Saved report also escapes HTML data', await page.locator('tbody img').count() === 0 && await page.evaluate(() => window.importInjected !== true));
    check('Imported accounts persist pending activation', db("SELECT COUNT(*) FROM users WHERE username IN ('" + first + "','" + second + "') AND status='PENDING_ACTIVATION' AND must_change_password=1") === '2');
    check('Imported phone is normalized', db("SELECT phone_normalized FROM users WHERE username='" + second + "'") === phoneB);
    check('Imported roles, warehouse and territory are assigned', db("SELECT COUNT(*) FROM user_roles ur JOIN users u ON u.id=ur.user_id WHERE u.username='" + first + "'") === '2' &&
      db("SELECT COUNT(*) FROM user_warehouses uw JOIN users u ON u.id=uw.user_id WHERE u.username='" + first + "'") === '1' &&
      db("SELECT COUNT(*) FROM user_territories ut JOIN users u ON u.id=ut.user_id WHERE u.username='" + first + "'") === '1');
    check('Audit records the session actor and excludes secrets', db("SELECT COUNT(*) FROM audit_logs a JOIN users u ON u.id=a.object_id WHERE u.username IN ('" + first + "','" + second + "') AND a.event_type='USER_CREATED' AND a.actor_user_id=1 AND a.before_values IS NULL AND a.after_values NOT LIKE '%password%' AND a.after_values NOT LIKE '%token%'") === '2');
    const messages = await fetch(process.env.S201_MAIL_URL || 'http://127.0.0.1:18085/api/v1/messages').then(r => r.json());
    check('Activation emails reach isolated Mailpit', messages.messages.some(m => m.To.some(to => to.Address === first + '@test.local')) &&
      messages.messages.some(m => m.To.some(to => to.Address === second + '@test.local')));
    await page.screenshot({ path: path.join(evidence, 'report-mobile.png'), fullPage: true });
    response = await context.request.post(base + '/admin/users/import', { form: { _csrf: csrf, action: 'confirm', token: previewToken }, maxRedirects: 0 });
    check('Repeated confirmation returns inline 400 without duplicate accounts', response.status() === 400 && (await response.text()).includes('Hãy tải lên tệp trước'));
    await importPage();
    await page.locator('[name=file]').setInputFiles({ name: 'bad.xlsx', mimeType: 'application/octet-stream', buffer: Buffer.from('bad') });
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/admin/users/import') && r.request().method() === 'POST'), page.getByRole('button', { name: 'Xem trước', exact: true }).click()]);
    check('File error is visible and accessible', await page.locator('#import-file-error').isVisible() && await page.locator('[name=file]').getAttribute('aria-invalid') === 'true');
    await page.screenshot({ path: path.join(evidence, 'error-mobile.png'), fullPage: true });
    await page.locator('[name=file]').setInputFiles({ name: 'invalid-row.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: workbook([['invalid', 'bad-email', 'Tên sai', 'bad', 'SALES', '', '']]) });
    await Promise.all([page.waitForResponse(r => r.url().endsWith('/admin/users/import') && r.request().method() === 'POST'), page.getByRole('button', { name: 'Xem trước', exact: true }).click()]);
    check('All-invalid preview disables confirmation button', await page.locator('.import-confirm button').isDisabled());
    await importPage();
    const retryStamp = String(Date.now()).slice(-8);
    const retryName = 'retry-' + retryStamp;
    const retryBytes = workbook([[retryName, retryName + '@test.local', 'Dòng thử lại', '07' + retryStamp, 'SALES', '', '']]);
    response = await upload(retryBytes);
    const oldPreview = await previewTokenFrom(response);
    await upload(Buffer.from('invalid replacement'));
    response = await context.request.post(base + '/admin/users/import', { form: { _csrf: await token(), action: 'confirm', token: oldPreview }, maxRedirects: 0 });
    check('Invalid replacement upload clears previous preview', response.status() === 400 && db("SELECT COUNT(*) FROM users WHERE username='" + retryName + "'") === '0');
    response = await upload(retryBytes);
    const wrongPreview = await previewTokenFrom(response);
    response = await context.request.post(base + '/admin/users/import', { form: { _csrf: await token(), action: 'confirm', token: wrongPreview + '-forged' }, maxRedirects: 0 });
    check('Forged preview token is rejected without writing', response.status() === 400 && db("SELECT COUNT(*) FROM users WHERE username='" + retryName + "'") === '0');
    execFileSync('docker', ['stop', 'codegym-s201-mail']);
    try {
      response = await upload(retryBytes);
      const failedMailPreview = await previewTokenFrom(response);
      response = await context.request.post(base + '/admin/users/import', { form: { _csrf: await token(), action: 'confirm', token: failedMailPreview }, maxRedirects: 0 });
      check('SMTP failure reports row error and leaves no account', response.status() === 200 && (await response.text()).includes('Gửi email thất bại') && db("SELECT COUNT(*) FROM users WHERE username='" + retryName + "'") === '0');
      check('SMTP failure leaves no successful audit', db("SELECT COUNT(*) FROM audit_logs WHERE event_type='USER_CREATED' AND after_values LIKE '%" + retryName + "%'") === '0');
    } finally { execFileSync('docker', ['start', 'codegym-s201-mail']); }
    await login('s201-revoked'); await importPage();
    const revokedToken = await token();
    db("DELETE ur FROM user_roles ur JOIN users u ON u.id=ur.user_id WHERE u.username='s201-revoked'");
    response = await context.request.post(base + '/admin/users/import', { form: { _csrf: revokedToken, action: 'confirm' }, maxRedirects: 0 });
    check('Revoked permission blocks the next POST', response.status() === 403);
    await login('admin'); await importPage();
    const expiryToken = await token();
    db("UPDATE user_sessions SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 MINUTE WHERE user_id=1 AND revoked_at IS NULL");
    response = await context.request.post(base + '/admin/users/import', { form: { _csrf: expiryToken, action: 'confirm' }, maxRedirects: 0 });
    check('Expired session redirects to login before import', response.status() === 303 && response.headers().location.includes('session_expired'));
    check('No JavaScript errors', errors.length === 0);
    fs.writeFileSync(path.join(evidence, 'results.json'), JSON.stringify({ base, checks, total: checks.length, at: new Date().toISOString() }, null, 2));
    console.log('TOTAL ' + checks.length);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
