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

Khi Tomcat đã chạy, kiểm tra nhanh health → login → dashboard:

```powershell
.\scripts\smoke-test.ps1
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

Sau năm lần nhập sai liên tiếp, tài khoản bị khóa tạm 15 phút. Request trong thời gian khóa không kéo dài thời gian khóa. Khi hết hạn, lần thử sai tiếp theo được tính lại từ lần một.

Phiên đăng nhập được lưu phía server, hết hạn sau 30 phút không hoạt động hoặc tối đa 8 giờ. Logout thu hồi phiên hiện tại; đổi mật khẩu thu hồi các phiên khác; đặt lại mật khẩu thu hồi toàn bộ phiên.

## Phạm vi hiện tại

Đăng nhập, khóa tạm, quản lý phiên, logout, quên mật khẩu và đổi mật khẩu đã được triển khai. Phân quyền chi tiết, quản trị người dùng, kho và địa bàn chưa nằm trong phần này.
