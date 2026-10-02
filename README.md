# Hệ thống quản lý bán hàng và kho

Ứng dụng Jakarta Servlet/JSP chạy trên Java 17 và Tomcat 10.1. MySQL và hộp thư kiểm thử Mailpit chạy bằng Docker.

## Yêu cầu

- JDK 17 tại `C:\Program Files\Java\jdk-17`
- Docker Desktop đang chạy
- Apache NetBeans 25 (tùy chọn, dùng cho Run/Debug)

Không cần cài Maven toàn hệ thống. `mvnw.cmd` tự tải Maven 3.9.16 và kiểm tra SHA-512 ở lần chạy đầu tiên.

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

## Chuẩn bị Tomcat cho NetBeans

```powershell
.\scripts\setup-tomcat.ps1
```

Script tải và kiểm tra checksum Tomcat 10.1.60, sau đó giải nén vào `.tools\apache-tomcat-10.1.60`. Thông tin tài khoản Tomcat Manager dành cho NetBeans nằm trong `.tools\netbeans-tomcat-credentials.txt`; toàn bộ thư mục `.tools` không được commit.

Trong NetBeans 25:

1. Mở trực tiếp thư mục repo dưới dạng Maven project.
2. Chọn Java Platform `C:\Program Files\Java\jdk-17`.
3. Vào **Tools → Servers → Add Server**, chọn Tomcat và trỏ tới `.tools\apache-tomcat-10.1.60`.
4. Nhập tài khoản trong `.tools\netbeans-tomcat-credentials.txt`.
5. Gán Tomcat vừa đăng ký cho project rồi chọn Run hoặc Debug.

WAR được tạo tại `target\ROOT.war` và ứng dụng chạy ở `http://localhost:8080/`.

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

Nhánh `develop` tích hợp Sprint 1 và Sprint 2: tài khoản, phân quyền, phân công kho/địa bàn, hồ sơ, nhập Excel, nhật ký, sản phẩm, nhóm hàng, quy đổi đơn vị, nhà cung cấp và bảng giá. Xem [hướng dẫn kiểm thử hợp nhất](docs/DEVELOP_TESTING.md) trước khi nghiệm thu.

Các luồng tạo đơn bán hàng, phiếu nhập/xuất kho, hóa đơn, công nợ và duyệt đơn dưới giá sàn chưa có giao diện nghiệp vụ hoàn chỉnh trong phạm vi này. Danh mục đã có kiểm tra tham chiếu giao dịch, lưu hệ số quy đổi và phiên bản giá để các luồng đó tích hợp sau.
