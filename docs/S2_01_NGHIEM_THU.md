# Nghiệm thu S2-01: Nhập người dùng từ Excel

Ngày kiểm tra: 02/10/2026 (Asia/Bangkok). Jira: SCRUM-27.

## Phạm vi và AC

Nhánh: `feature/S2-01-nhap-nguoi-dung-excel`, đồng bộ từ `develop` tại `2e14375`.
Giữ endpoint `GET|POST /admin/users/import`, tệp mẫu bảy cột và preview lưu phía server.
Không thay đổi schema; không đưa S2-02 chưa duyệt vào nhánh này.

| AC khách hàng | Cách kiểm chứng | Kết quả |
| --- | --- | --- |
| Tải được tệp mẫu | HTTP tải XLSX; controller test đọc lại bằng Xlsx, đúng bảy tiêu đề | Đạt |
| Xem trước và báo lỗi từng dòng trước khi nhập | Tệp xen kẽ hợp lệ/lỗi; số dòng, dữ liệu, tổng hợp hợp lệ/lỗi; database chưa có tài khoản trước xác nhận | Đạt |
| Bỏ qua dòng lỗi, nhập dòng hợp lệ và báo cáo tổng kết | Nhập thật trên Tomcat và MySQL; đối chiếu tài khoản, vai trò, kho/địa bàn, email kích hoạt, audit | Đạt |

## Ma trận kiểm thử

| Nhóm | Dữ liệu / thao tác | Kết quả yêu cầu và bằng chứng |
| --- | --- | --- |
| Mẫu / tệp | Mẫu đúng, sai tiêu đề, tệp hỏng, không có dữ liệu, dòng trống | Mẫu đọc lại được; tệp sai trả 400 tại trường file; unit/controller, IT và HTTP |
| Giới hạn upload | Thiếu tệp, trên 10 MiB, vượt cả giới hạn request multipart | Không nhập; lỗi file 400, không chuyển thành 500; controller/HTTP |
| Biên trường | Tên trim 0/1/2/150/151 ký tự, tên tiếng Việt/dấu nháy, username/email sai, cột thừa hoặc thiếu | Dòng sai có lỗi; không giữ chỗ các khóa của dòng lỗi; validator/IT |
| Điện thoại | Di động, số cố định, +84 và dấu phân cách; rỗng/chữ/sai độ dài/đầu số/số nước ngoài | Chuẩn hóa số hợp lệ, từ chối số sai; validator/IT/HTTP |
| Trùng | Trùng username/email/SĐT trong tệp và database, khác hoa/thường, +84 cùng số nội địa | Báo lỗi; dòng trùng không làm dòng hợp lệ tiếp theo bị từ chối; IT/HTTP |
| Phân công | Nhiều vai trò, kho/địa bàn thật, mã không tồn tại, vai trò kho thiếu kho | Lưu đúng phân công, báo lỗi cụ thể khi sai; IT/HTTP |
| Xác nhận | Sai owner/token, hết hạn đúng 30 phút, gửi lại, hai lần đồng thời | Không ghi thêm; chỉ một lần claim; unit/IT/HTTP |
| Thay đổi sau preview | Tài khoản trùng được tạo sau preview, kho bị xóa, upload thay thế không hợp lệ | Kiểm tra lại dữ liệu; không dùng preview cũ sau upload lỗi; IT/HTTP |
| Quyền | Bảy vai trò; Admin + Sales; thu hồi quyền trước xác nhận và giữa các dòng | Chỉ USER_MANAGE được nhập; thu hồi quyền trả 403 và dừng trước dòng tiếp theo; IT/HTTP |
| Danh tính request | Gửi userId/actorId/username/role giả trong query và POST | Actor audit lấy từ phiên, dữ liệu tài khoản lấy từ preview server; controller/HTTP |
| CSRF / phiên | CSRF thiếu/sai, guest GET/POST, phiên hết hạn | 403 hoặc chuyển login trước khi nhập; controller/HTTP |
| HTML / bí mật | Họ tên chứa HTML và dấu nháy | Escape ở preview/report; không thực thi script; audit không chứa password/token; IT/HTTP |
| Email / kích hoạt | Email thành công, SMTP thất bại, token dùng lại | Tài khoản pending và buộc đổi mật khẩu; token dùng một lần; dòng gửi mail lỗi rollback và không có audit thành công; IT/HTTP |
| Lỗi audit / database | Trigger gây lỗi audit trong MySQL test riêng; lỗi hệ thống ở controller | Rollback tài khoản; lỗi hệ thống không bị đổi thành lỗi nhập liệu; IT/controller |
| UI / hồi quy | Bàn phím, desktop 1366px, 360px, bảng rộng, form lỗi, route Portal khác | Không tràn trang/chồng chữ; bảng cuộn riêng; kết quả thực tế ghi Đã nhập; screenshot/HTTP |

## Lệnh và kết quả

Đặt `JAVA_HOME=C:\Program Files\Java\jdk-17`, Docker Desktop phải chạy.

```powershell
.\mvnw.cmd -B -ntp '-Dtest=UserImportValidatorTest,ImportPreviewTest,UserImportServletTest,XlsxTest' '-Dit.test=UserImportAcceptanceIT' verify
.\mvnw.cmd -B -ntp clean verify
.\scripts\preview-user-import.ps1
# PLAYWRIGHT_MODULE trỏ tới module playwright có sẵn; chạy bằng Node.js.
node scripts/test-user-import-ui.cjs
```

- Bộ tập trung: 35 unit + 19 integration MySQL, không lỗi/không bỏ qua.
- Bộ đầy đủ: `clean verify` hoàn tất 14:42:21 ngày 02/10/2026, 90 unit + 43 integration, không lỗi/không bỏ qua; gồm 17 ca hồi quy Sprint 1-2 và ca 5.000 SKU.
- HTTP/UI: 53 kiểm tra đạt trên WAR bàn giao, gồm giả mạo actor, SMTP lỗi, CSRF, phiên, hai kích thước màn hình và hồi quy route Portal khác.
- Sau verify chỉ chỉnh CSS bỏ khung upload; đóng gói lại bằng `-DskipTests package` rồi chạy lại toàn bộ 53 kiểm tra HTTP/UI. Không dùng lần đóng gói này thay cho bằng chứng `clean verify` ở trên.
- Bằng chứng local: `.tools/s201-full-verify.log`, `.tools/s201-evidence/results.json`, `mixed.xlsx` và ảnh `preview-desktop.png`, `preview-mobile.png`, `report-mobile.png`, `error-mobile.png`.
- Integration test hồ sơ nhập không bật `disabledWithoutDocker`; bộ đầy đủ phải có số skipped bằng 0 mới được tính đạt.

## Bản xem và giới hạn

- URL mặc định: http://localhost:18083/admin/users/import; đăng nhập `admin/admin123`.
- Mailpit riêng: http://localhost:18085; database riêng `codegym-s201-preview`, MySQL `127.0.0.1:13309`.
- Tải mẫu; đổi username/email/SĐT thành dữ liệu chưa có; nhập tệp có dòng đúng/sai, xem trước và xác nhận. Đối chiếu danh sách người dùng và thư kích hoạt.
- HTTP/UI test tạo tài khoản fixture và chỉ chạy trên môi trường preview riêng; không chạy trên database thật của người dùng.
- Mỗi dòng hợp lệ là một giao dịch độc lập. Nếu lỗi hệ thống hoặc mất quyền giữa chừng, dòng đã commit trước đó vẫn tồn tại; không tuyên bố rollback toàn bộ tệp.
- Email được gửi trong giao dịch theo kiến trúc hiện có, không có transactional outbox. Không cam kết nguyên tử giữa SMTP bên ngoài và commit database.
- Chưa push/hợp nhất; S2-02 và bản chạy cổng 18082 được giữ nguyên. Các story tiếp theo chờ lượt nghiệm thu.
