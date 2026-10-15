# Hệ thống quản lý bán hàng và kho

Ứng dụng Jakarta Servlet/JSP chạy trên Java 17 và Tomcat 10.1. MySQL và hộp thư kiểm thử Mailpit chạy bằng Docker.

## Yêu cầu

- JDK 17 tại `C:\Program Files\Java\jdk-17`
- Docker Desktop đang chạy
Không cần cài Maven toàn hệ thống. `mvnw.cmd` tự tải Maven 3.9.16 và kiểm tra SHA-512 ở lần chạy đầu tiên.

Giao diện dùng Tailwind CLI 4.3.3 và Font Awesome Free 7.3.1 tự host. Chuẩn bị CLI một lần trước build; script kiểm SHA-256 trên Windows/Linux:

```powershell
.\scripts\setup-tailwind.ps1
```

```sh
sh scripts/setup-tailwind.sh
./mvnw clean verify
```

Maven biên dịch `src/main/tailwind/app.css` thành `assets/css/ui.css` trong WAR. Nguồn utility chỉ gồm JSP/tag và JS đã khai báo; token, component và biến thể riêng trang nằm cùng thư mục Tailwind. Trình duyệt hỗ trợ: Chrome 111+, Safari 16.4+, Firefox 128+.

## Khởi động dịch vụ local và kiểm tra build

```powershell
.\scripts\db-start.ps1
.\mvnw.cmd clean verify
```

Hoặc chạy toàn bộ kiểm tra điều kiện bằng:

```powershell
.\scripts\verify.ps1
```

Khi Tomcat đã chạy, kiểm tra health, đăng nhập có CSRF, dashboard và 12 trang quản trị/danh mục:

```powershell
.\scripts\smoke-test.ps1
# Nếu Tomcat dùng cổng khác:
.\scripts\smoke-test.ps1 -BaseUrl http://localhost:18080
```

Dừng MySQL và Mailpit mà vẫn giữ dữ liệu:

```powershell
.\scripts\db-stop.ps1
```

## Tài khoản khởi tạo local

- Username: `admin`
- Email: `admin@local.test`
- Password: `admin123`

Tài khoản này chỉ dùng trong môi trường phát triển local. Migration lưu BCrypt hash, không lưu mật khẩu thô trong database.

Email đặt lại mật khẩu được gửi vào Mailpit tại `http://localhost:8025/`; không có email nào được gửi ra Internet.

## Endpoint

- `GET /health`: kiểm tra ứng dụng và kết nối database.
- `GET|POST /login`: đăng nhập bằng username hoặc email.
- `POST /logout`: thu hồi phiên hiện tại và đăng xuất.
- `GET|POST /forgot-password`: yêu cầu liên kết đặt lại mật khẩu.
- `GET|POST /reset-password`: đặt mật khẩu mới bằng token một lần.
- `GET|POST /account/change-password`: đổi mật khẩu khi đang đăng nhập.
- `GET /dashboard`: yêu cầu session đã xác thực.
- `GET /admin/users`: tìm kiếm, lọc và phân trang danh sách tài khoản (quyền `USER_MANAGE`).
- `GET|POST /admin/users/new`: tạo tài khoản chờ kích hoạt và gửi email kích hoạt (quyền `USER_MANAGE`).
- `GET|POST /admin/users/edit?id={id}`: sửa thông tin tài khoản (quyền `USER_MANAGE`).
- `GET|POST /activate`: kích hoạt tài khoản bằng token một lần.
- `GET|POST /admin/roles`, `/admin/assignments`, `/admin/scopes`: phân quyền, phân công nhiều vai trò, kho và địa bàn.
- `GET|POST /admin/users/import`: xem trước và nhập người dùng từ Excel.
- `GET /admin/audit`: lọc nhật ký thao tác.
- `GET|POST /account/profile`, `/account/avatar`: hồ sơ và ảnh đại diện.
- `GET|POST /catalog/products`, `/catalog/categories`, `/catalog/units`, `/catalog/suppliers`: danh mục Sprint 2.
- `GET|POST /catalog/products/import`: xem trước và nhập/cập nhật SKU từ Excel.
- `GET|POST /pricing/lists`: bảng giá theo nhóm khách hàng, thời gian hiệu lực và phiên bản.

Sau năm lần nhập sai liên tiếp, tài khoản bị khóa tạm 15 phút. Request trong thời gian khóa không kéo dài thời gian khóa. Khi hết hạn, lần thử sai tiếp theo được tính lại từ lần một.

Phiên đăng nhập được lưu phía server, hết hạn sau 30 phút không hoạt động hoặc tối đa 8 giờ. Logout thu hồi phiên hiện tại; đổi mật khẩu thu hồi các phiên khác; đặt lại mật khẩu thu hồi toàn bộ phiên.

Tài khoản do quản trị viên tạo phải kích hoạt qua email trước khi đăng nhập, sau đó bắt buộc đổi mật khẩu tạm. Username, email và số điện thoại được chuẩn hóa và có ràng buộc duy nhất tại database. Danh sách tài khoản tìm theo tên, username hoặc số điện thoại; lọc theo vai trò/trạng thái và hiển thị 20 tài khoản mỗi trang. Các vai trò local gồm `ADMIN`, `SALES`, `WAREHOUSE`, `SALES_MANAGER`, `WAREHOUSE_MANAGER`, `ACCOUNTANT` và `DIRECTOR`. Giá vốn chỉ dành cho `SALES_MANAGER`, kể cả khi xem nhật ký.

## Phạm vi hiện tại

Nhánh `develop` tích hợp Sprint 1, Sprint 2 và Sprint 3: tài khoản, phân quyền, phân công kho/địa bàn, hồ sơ, nhập Excel, nhật ký, sản phẩm, nhóm hàng, quy đổi đơn vị, nhà cung cấp và bảng giá.

Sprint 3 bổ sung đại lý, điểm giao, hạn mức, bàn giao, khóa giao dịch, chiết khấu, lịch sử giá, đơn bán hàng và cổng đại lý. MVP tiếp nối luồng nhập kho → đơn → tiếp nhận → giữ tồn → xuất/giao từng phần → hóa đơn → thu → công nợ và báo cáo; trả hàng tốt/hỏng giảm nợ theo hóa đơn gốc.


## Quy tắc chiết khấu và duyệt đơn

- Cộng số lượng của cùng một SKU trên mọi dòng và đơn vị, sau khi quy đổi về đơn vị cơ sở. Chính sách nhóm hàng được xét riêng cho từng SKU; không cộng lượng của các SKU khác nhau để đạt bậc.
- So sánh lợi ích bằng tiền của các chính sách SKU và nhóm hàng còn hiệu lực cho nhóm khách hàng trong ngày tính giá tại Việt Nam. Chọn mức giảm có lợi nhất, không cộng dồn. Nếu bằng nhau, chọn chính sách có ID nhỏ hơn để kết quả ổn định.
- Tính toán dùng `BigDecimal`; số tiền dòng và tổng giữ bốn chữ số thập phân với `HALF_UP` như dữ liệu lưu. Giao diện đơn và cổng hiển thị đồng nguyên, `HALF_UP`, dùng chuỗi định dạng từ server. Việc làm tròn hiển thị không thay đổi phép xét giá sàn.
- Giá sau chiết khấu dưới sàn vẫn báo giá được nhưng gửi đơn chuyển thành `PENDING_APPROVAL`. Đơn trên hoặc bằng sàn chuyển thẳng `SUBMITTED`. Đơn chờ duyệt chưa được coi là đã tiếp nhận và không sửa trực tiếp.
- Quản lý kinh doanh hoặc Ban giám đốc có `ORDER_APPROVE` được chấp thuận/từ chối. Người tạo, người yêu cầu và người tiếp quản không được tự duyệt, kể cả có nhiều vai trò. Ban giám đốc không được cấp thêm quyền sửa đơn hoặc xem giá vốn.
- Người duyệt xem báo giá lúc yêu cầu và báo giá hiện tại. Khi giá, chiết khấu, nhóm hoặc ngày giá thay đổi, phải xác nhận báo giá mới; server kiểm lại trong giao dịch quyết định. Thiếu giá, SKU không hợp lệ hoặc sai phạm vi vẫn chặn chấp thuận.
- Chấp thuận lưu snapshot cuối cùng và nhật ký trong cùng giao dịch. Từ chối bắt buộc lý do, chuyển `REJECTED`, chỉ đọc; muốn tiếp tục phải tạo đơn mới. Khóa phiên bản và mã gửi/quyết định bảo vệ việc gửi lại hoặc thao tác đồng thời.

## Luồng MVP và các màn hình

| Màn hình | Đường dẫn | Người thao tác |
| --- | --- | --- |
| Nhập nhà cung cấp, xem/in phiếu | `/inventory/receipts` | Kho, Trưởng kho được giao kho |
| Tồn khả dụng | `/inventory/stock` | Kho theo kho được giao; NVKD theo đại lý phụ trách; QLKD/Giám đốc/Kế toán đọc tổng |
| Thẻ kho và nguồn phát sinh | `/inventory/card` | Kho, Trưởng kho được giao kho |
| Đơn nội bộ, tiếp nhận, duyệt, hủy, in HTML | `/orders` | NVKD theo đại lý phụ trách, QLKD; Giám đốc duyệt theo quyền |
| Đơn đại lý | `/portal/orders` | Tài khoản đại lý liên kết, không tự xác nhận/duyệt |
| Soạn, xuất, giao, nhận hàng quay về | `/fulfillment/shipments` | Kho được giao; chỉ Trưởng kho nhận hàng quay về |
| Hóa đơn nội bộ và in HTML | `/accounting/invoices` | Kế toán phát hành; đọc theo phạm vi kinh doanh |
| Thu tiền, đảo phiếu thu, in HTML | `/accounting/payments` | Kế toán; đảo toàn phiếu có lý do |
| Trả hàng, nhận tốt/hỏng, giảm trừ | `/accounting/returns` | NVKD/QLKD lập; Trưởng kho nhận tại kho nguồn; Kế toán giảm trừ |
| Công nợ hiện tại | `/accounting/debt` | QLKD/Giám đốc/Kế toán; NVKD chỉ đại lý đang phụ trách |
| Doanh số theo kỳ | `/reports/sales` | Phạm vi đọc như công nợ |

Quyền và phạm vi kiểm trên server cho từng action, cả POST trực tiếp, replay, API và trang in. Admin không có quyền nghiệp vụ mặc định; vai trò đại lý không nhận quyền nội bộ. Nhiều vai trò vẫn phải thỏa điều kiện kho/đại lý và chống tự duyệt. Giá vốn chỉ QLKD.

Đơn nội bộ thường được xác nhận và giữ đủ hàng khi gửi thành công. Đơn cổng thường chờ NVKD phụ trách hiện tại xác nhận; gửi chưa tạo giữ hoặc cam kết. Đơn cổng ngoại lệ được quản lý duyệt thay, không cần thêm bước NVKD. Giá/nợ/tồn được tính lại khi tiếp nhận; quá hạn và thiếu tồn luôn chặn. Đổi người phụ trách hoặc thu hồi quyền có hiệu lực ngay.

FIFO dùng ngày nhập rồi ID lô. Các lần nhập cùng mã lô vẫn tách nguồn. Giữ chỗ 24 giờ; bắt đầu soạn bảo vệ phần giữ chưa xuất. Xuất thiếu để lại phần còn cần giao; trả hàng không tự mở lại nhu cầu đơn. Xuất 8/nhận 6 chỉ lập hóa đơn 6, tiền của 2 chờ quay về vẫn là cam kết đến khi Trưởng kho nhận lại. Hàng tốt về lô nguồn giữ tuổi lô; hàng hỏng không cộng tồn bán được.

Mỗi lần giao có một hóa đơn. Phát hành tự cấn dư có đã có trước đó trong cùng transaction; không tạo phiếu thu mới hoặc giảm nợ lần nữa. Thu chỉ phần còn thiếu, tự phân bổ hóa đơn cũ nhất theo ngày/ID. Trả tốt và hỏng đều giảm trừ theo giá hóa đơn gốc sau khi kho nhận; trả sau thanh toán tạo dư có cho hóa đơn sau. Doanh số = hóa đơn − giảm trừ, thu tiền không tăng doanh số. Bộ lọc kỳ doanh số không làm đổi số dư công nợ hiện tại.

Mỗi command dùng actor, ID, expectedVersion, UUID và input nghiệp vụ. Quyền được kiểm trước replay; cùng UUID/payload trả kết quả cũ, khác payload xung đột, version cũ HTTP 409. Public service mở một transaction; helper liên service dùng cùng Connection. Tiền BigDecimal scale4/HALF_UP, lượng nhập scale6, cơ sở scale12; phân bổ tích lũy nhận phần dư ở lần cuối. Lỗi audit rollback cả số chứng từ, tồn, cam kết, công nợ và phân bổ.

## Kiểm thử và phát hành MVP

Chạy `mvnw.cmd -B -ntp clean verify` bằng JDK17. MySQL Testcontainers dùng database tạm, Clock cố định và barrier cho các ca đồng thời. Các gate gồm nâng cấp V029 giữ snapshot/lịch sử, tồn5/hai đơn4, hạn mức/hai đơn tranh nhau, expiry/soạn, hàng chờ quay về, trả hỏng, dư có/concurrency/replay, tiền lẻ, rollback audit và sai role/kho/đại lý. Luồng số liệu chuẩn kết thúc tồn12, giữ0, cam kết0, nợ0, doanh số thuần800.000đ và thu800.000đ.

`scripts/deploy-mvp.ps1` phát hành **develop**, kiểm evidence/WAR cùng cây code, cổng8081, container/volume hiện tại và đúng tiến trình Tomcat. Cấu hình và credentials giữ local dưới `.tools`; tài khoản migration riêng với runtime. Trước nâng cấp dừng đúng Tomcat, sao lưu DB/uploads/WAR/cấu hình, rồi `scripts/verify-mvp-backup.ps1` phục hồi SQL trên container tạm, thử nâng cấp và đối chiếu lịch sử/tổng tiền. Runtime chỉ validate schema. Nếu migration/health lỗi, phục hồi DB và WAR/cấu hình cũ trước khi bật lại. Mô phỏng lỗi chỉ dùng Preview với container tạm; không dùng public để diễn tập.

Phần hoãn: mã vạch, PDF, FEFO/cảnh báo HSD, chuyển kho, kiểm kê, ảnh chứng từ, gộp soạn/giao/hóa đơn, đối trừ thủ công, Excel/biểu đồ/báo cáo nâng cao và cổng tải hóa đơn. Trang in MVP dùng HTML.

## Kiểm thử và phát hành nền UI

`scripts/run-ui-regression.cjs` chạy các test trình duyệt Sprint 3 trên Preview `18087`, sau khi kiểm database có nhãn tạm, dùng tmpfs và chỉ bind loopback `13318`. `--prepare` chuẩn bị quyền mặc định và dữ liệu synthetic cho kiểm thử; chỉ dùng trên fixture cô lập. `scripts/test-ui-common.cjs` kiểm lưu phân quyền, CSRF, ảnh đại diện, bàn phím, form không JavaScript và bộ lọc phân trang.

Bộ ảnh ở `scripts/visual` dùng Playwright Linux đã ghim, năm cỡ màn hình và ngưỡng khác biệt 1%. Đặt `UI_REVIEW_CANDIDATE=1` để lưu ảnh ứng viên riêng trong `.tools/ui-evidence/candidate`; chỉ cập nhật ảnh chuẩn trong `__screenshots__` sau khi người dùng duyệt.

Phát hành giao diện dùng `scripts/deploy-mvp.ps1 -UiOnly`. Preview nhận nhánh UI hoặc nhánh tích hợp; Public yêu cầu `main` đã nghiệm thu. Script kiểm migration giữa hai SHA, validate checksum và migration chờ, giữ `MIGRATION_ENABLED=false`. Cả phục hồi tự động khi health lỗi và `-RollbackWar` chỉ thay WAR, giữ database, uploads và cấu hình. Luồng phát hành có thay đổi schema tiếp tục dùng quy trình MVP riêng ở trên.
