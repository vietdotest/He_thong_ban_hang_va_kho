# Nghiệm thu S2-02: Xem và cập nhật hồ sơ cá nhân

Ngày kiểm tra: 02/10/2026, múi giờ Việt Nam (UTC+7).
Story: [SCRUM-28](https://vietdo.atlassian.net/browse/SCRUM-28).
Nhánh xem trước: `feature/S2-02-ho-so-ca-nhan`, đã đồng bộ `develop` tại `2e14375`.

## Tiêu chí khách hàng

| AC trên Jira | Thực hiện | Bằng chứng nghiệm thu |
| --- | --- | --- |
| Sửa được họ tên, số điện thoại | Chỉ cập nhật hai trường của người đăng nhập; chuẩn hóa số điện thoại, lưu audit trước/sau trong cùng giao dịch. | `ProfileServletTest`, `ProfileAcceptanceIT`: lưu thành công, tải lại/đăng nhập lại, audit đúng người và rollback khi audit lỗi. |
| Không tự đổi được tài khoản, vai trò, kho và địa bàn | Hiển thị thông tin phân công chỉ đọc; lấy user ID từ phiên, bỏ qua tham số bổ sung trong request. | Unit test xác nhận không đọc tham số ID/phân quyền. HTTP giả mạo request với hai tài khoản và MySQL đối chiếu tài khoản, vai trò, kho, địa bàn không bị thay đổi. |
| Kiểm tra định dạng số điện thoại Việt Nam | Dùng `PhoneNumber.normalize`: di động/cố định, `+84`, dấu phân cách; lỗi trả 400 tại trường SĐT, giữ dữ liệu nhập. | `ProfileValidatorTest`, `PhoneNumberTest`, HTTP/UI kiểm số sai và số trùng; test MySQL đồng thời xác nhận đúng một cập nhật thành công. |

## Bộ test case

| Mã | Nhóm | Dữ liệu/thao tác | Kết quả mong đợi và đã kiểm tra |
| --- | --- | --- | --- |
| TC01 | Chức năng | Bảy vai trò hệ thống cập nhật hồ sơ riêng | Lưu được khi có PROFILE; tên trim, SĐT chuẩn hóa, version tăng, audit đúng actor/thời điểm/trước/sau. |
| TC02 | Giá trị biên | Tên null/rỗng/khoảng trắng/1/2/150/151 ký tự | Chỉ tên sau trim từ 2 đến 150 ký tự được chấp nhận. |
| TC03 | Nội dung | Tên tiếng Việt, dấu nháy, HTML/script | Tên hợp lệ được lưu như dữ liệu; JSP escape cả giá trị form và tên hiển thị. Không chạy script. |
| TC04 | SĐT hợp lệ | Đầu số 03/05/07/08/09; số cố định 02; +84; khoảng trắng/dấu chấm/gạch ngang/ngoặc | Chuẩn hóa về dạng nội địa. |
| TC05 | SĐT không hợp lệ | Null/rỗng, chữ, đầu số 01, quá ngắn/dài, mã +1, +840 | Trả lỗi tại trường phone, không cập nhật DB hoặc ghi audit thành công. |
| TC06 | Trùng lặp | Số hiện tại của mình; số của tài khoản khác dưới dạng +84 | Số của mình được phép; số người khác trả 400 với lỗi SĐT. |
| TC07 | Đồng thời | Hai tài khoản vượt pre-check rồi cùng cập nhật một SĐT | Một thành công, một lỗi theo trường; một người sở hữu số và một audit. Dùng barrier trước UPDATE thật trên MySQL. |
| TC08 | Phân quyền/IDOR | Gửi userId/id/username/email/role/warehouseId/territoryId giả mạo | Chỉ tên/SĐT của chủ phiên thay đổi; tài khoản khác và toàn bộ phân công giữ nguyên. |
| TC09 | CSRF | Thiếu hoặc sai _csrf | HTTP 403; không gọi service cập nhật. |
| TC10 | Phiên/quyền | Chưa đăng nhập, phiên hết hạn, thiếu PROFILE | HTTP 303 tới login hoặc HTTP 403 theo trường hợp; không lưu hồ sơ. |
| TC11 | Giao dịch | Gây SQLException tại thao tác ghi audit | Giao dịch MySQL rollback tên, SĐT, version; không có audit thành công. Không dùng quyền SUPER/trigger. |
| TC12 | Lỗi hệ thống | Service báo lỗi DB ngoài lỗi trùng SĐT | HTTP 500, không chuyển thành thông báo validation sai. |
| TC13 | UI lỗi | Nhập tên và SĐT sai | HTTP 400 trên trang hồ sơ, giữ nguyên dữ liệu đã nhập, lỗi có aria-invalid/aria-describedby và aria-live. |
| TC14 | UI thành công | Lưu, tải lại, logout và login lại | Thông báo thành công, dữ liệu mới tồn tại. |
| TC15 | Bàn phím/responsive | Tab từ tên đến SĐT/nút lưu, Enter; desktop 1366px/mobile 360px | Thao tác được bằng bàn phím, không tràn ngang, không chồng nội dung. |
| TC16 | Thông tin chỉ đọc | Có/không có phân công kho và địa bàn | Hiển thị danh sách được phân công hoặc “Chưa phân công”; không có trường POST để sửa. |

## Chạy lại kiểm thử

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\mvnw.cmd '-Dtest=ProfileValidatorTest,ProfileServletTest,PhoneNumberTest' '-Dit.test=ProfileAcceptanceIT' verify
.\mvnw.cmd clean verify
```

`ProfileAcceptanceIT` yêu cầu Docker hoạt động; không tự bỏ qua khi thiếu Docker. Database integration được Testcontainers tạo riêng, không dùng database ứng dụng.

## Bản xem trước local

```powershell
.\scripts\preview-profile.ps1
```

Script yêu cầu WAR đã build, JDK 17 và Tomcat 10.1. Mặc định tìm Tomcat trong thư mục repo cũ cùng cấp; có thể truyền `-TomcatHome`. Cổng ứng dụng mặc định 18082, database 13308; có thể truyền `-Port` và `-DatabasePort` khi khởi tạo container mới.

Database `codegym-s202-preview` độc lập, chỉ bind localhost. Runtime và upload nằm trong `.tools/`, không commit. Script in PID để dừng đúng tiến trình Tomcat bằng `Stop-Process -Id <PID>`; dừng database bằng `docker stop codegym-s202-preview` vẫn giữ dữ liệu.

URL xem hồ sơ: `http://localhost:18082/account/profile`.
Tài khoản fixture hiện đã chuẩn bị: `profile-sales` / `admin123`, có vai trò kinh doanh, Kho Hà Nội và Miền Bắc. `admin` / `admin123` dùng kiểm tra chưa phân công kho/địa bàn. Fixture chỉ có trong database xem trước của lần nghiệm thu này.

Ảnh và kết quả HTTP/UI local: `.tools/s202-desktop.png`, `.tools/s202-mobile.png`, `.tools/s202-error-desktop.png`, `.tools/s202-error-mobile.png`, `.tools/s202-http-ui-results.json`. Kiểm tra 30 điều kiện HTTP/UI bằng Playwright trên Tomcat thật đã đạt.

## Kết quả và điểm bàn giao

- 23 ca validation hồ sơ và 8 ca controller mới đã đạt; 2 test SĐT hiện có cũng đạt.
- 7 integration test hồ sơ trên MySQL 8.4.11 đã đạt, không bỏ qua.
- `mvnw.cmd clean verify`: BUILD SUCCESS lúc 13:26 ngày 02/10/2026 (UTC+7), 91 unit test và 31 integration test, không lỗi/không bỏ qua. Bài nhập 5.000 SKU và kiểm nâng cấp schema của Sprint 1–2 cũng đạt.
- Log tổng thể local: `.tools/s202-full-verify.log`; báo cáo XML: `target/surefire-reports/` và `target/failsafe-reports/`. Tổng thể chạy trong 14 phút trên máy này.
- Luồng ảnh đại diện được giữ nguyên; không coi lượt này là nghiệm thu toàn bộ S2-03.
- Bàn giao bản local và commit trên nhánh S2-02 để người dùng xem trước; chưa hợp vào develop/main, chưa push hoặc đổi trạng thái Jira trong lượt này.
