# Nâng cấp database bằng tài khoản riêng

Web mặc định chỉ **kiểm tra migration**, không tự thay đổi schema (`MIGRATION_ENABLED=false`). Database mới hoặc còn migration chờ sẽ chặn khởi động. Worker nhập Excel mặc định vẫn bật; `IMPORT_WORKER_ENABLED=false` chỉ dùng cho tiến trình giao diện/QA hoặc bảo trì không nhận tác vụ.

## Chuẩn bị

- Build bằng JDK 17 và Maven Wrapper; kiểm thử trên database/SMTP QA riêng.
- Dừng nhận tác vụ mới, chờ tác vụ nhập chạy hết và xác nhận không còn lần gửi email chưa rõ kết quả. Không kết thúc worker đang gửi email để làm backup.
- Backup database nhất quán, thư mục ảnh/upload và bản WAR đang sử dụng. Thử phục hồi bản sao trước phát hành; lưu checksum và lịch sử Flyway.
- Tài khoản web chỉ có quyền DML cần thiết trên database của ứng dụng (`SELECT`, `INSERT`, `UPDATE`, `DELETE`), không cấp `SUPER`, `CREATE`, `ALTER`, `DROP`, `TRIGGER`.
- Tài khoản migration riêng cần các quyền DDL tương ứng với migration; MySQL bật binary log có thể yêu cầu đặc quyền bổ sung cho trigger. DBA giải quyết ở tài khoản phát hành; không tự đổi cấu hình GLOBAL trên database thực và không nâng quyền tài khoản web.

## Chạy một lần

Đặt `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` cho đúng database/tài khoản runtime; đặt `MIGRATION_USERNAME`, `MIGRATION_PASSWORD` cho tài khoản phát hành bằng kênh bí mật. Không đưa mật khẩu vào Git, dòng lệnh, ảnh chụp hoặc báo cáo.

Chạy `scripts/migrate-database.ps1` không có `-Run` chỉ hiển thị hướng dẫn, không mở kết nối. Sau khi xác nhận database đích và backup, chạy với `-Run`; script yêu cầu các biến môi trường rõ ràng và không dùng database mặc định. Không chỉnh sửa checksum migration lịch sử.

Sau thành công, bỏ thông tin migration khỏi môi trường của web và đặt `MIGRATION_ENABLED=false`. Bật một tiến trình worker mặc định; các tiến trình giao diện phụ đặt `IMPORT_WORKER_ENABLED=false`. Kiểm tra health, đăng nhập, phân quyền, checksum/schema và dữ liệu cũ trước mở truy cập.

## Khi nâng cấp lỗi hoặc cần rollback

Không xóa hàng trong lịch sử Flyway hoặc dùng `repair` để che lỗi. MySQL DDL không rollback như transaction nghiệp vụ: giữ nguyên lỗi/bằng chứng, kiểm tra trạng thái schema rồi quyết định sửa migration mới hoặc phục hồi backup trên bản sao đã xác minh.

Rollback bản phát hành bằng database, ảnh/upload và WAR tương ứng tại cùng thời điểm; không chạy WAR cũ trên schema mới mà chưa kiểm tra tương thích. Ghi lại mọi giao dịch phát sinh sau backup; cần đối soát và chuyển tiếp trước phục hồi để không mất dữ liệu. Email đã gửi không thể thu hồi: đối soát journal/SMTP, các dòng `REVIEW` không tự gửi lại.

Việc triển khai public chỉ thực hiện khi được chỉ định và đã qua các cổng nghiệm thu Sprint 3.
