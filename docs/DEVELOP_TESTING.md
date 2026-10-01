# Kiểm thử bản hợp nhất trên develop

## Chuẩn bị

```powershell
git switch develop
git pull --ff-only origin develop
.\scripts\db-start.ps1
.\scripts\verify.ps1
```

Docker Desktop phải chạy để integration test dùng MySQL thật qua Testcontainers. Build tạo `target\ROOT.war`; triển khai WAR lên Tomcat 10.1 theo README. Khi ứng dụng đã chạy:

```powershell
.\scripts\smoke-test.ps1 -BaseUrl http://localhost:8080
```

Script dùng tài khoản local `admin/admin123` trên database khởi tạo. Nếu tài khoản này đã được đổi mật khẩu, dùng môi trường kiểm thử riêng với dữ liệu khởi tạo; không xóa database đang dùng để chạy smoke test.

## Bằng chứng kiểm tra ngày 01/10/2026

- `scripts/verify.ps1`: 60 unit test và 24 integration test, không lỗi và không bỏ qua test.
- Integration test kiểm tra nâng cấp từ schema V004, giữ nguyên người dùng, vai trò và phiên cũ; nhập 5.000 SKU; chống xung đột bảng giá; rollback khi email hoặc ghi audit thất bại.
- Unit test Excel bổ sung trường hợp XML có DOCTYPE/XXE, công thức và vượt giới hạn 10.000 dòng.
- Smoke test đã qua trên Tomcat 10.1.60 tại `http://localhost:18080`: health, đăng nhập với CSRF, phiên đăng nhập, dashboard và 12 trang có xác thực. Đây là kiểm tra đọc trang; vẫn cần nghiệm thu thao tác nghiệp vụ bên dưới.

## Nghiệm thu thủ công

Tạo tài khoản cho `SALES_MANAGER`, `WAREHOUSE` và `SALES`; gán kho/địa bàn trước khi kiểm tra quyền kho. Email thử nghiệm nằm trong Mailpit. Mỗi lần kiểm tra quyền cần đăng nhập bằng đúng tài khoản, kể cả truy cập URL trực tiếp.

| Phần | Thao tác và kết quả cần kiểm tra |
| --- | --- |
| Sprint 1 | Đăng nhập; kích hoạt và đổi mật khẩu tạm; quên mật khẩu; phân quyền menu/URL; phân công nhiều vai trò và kho/địa bàn; khóa có lý do và thu hồi phiên; trang 403/404. |
| S2-01 | Tải mẫu người dùng, nhập tệp có dòng hợp lệ/lỗi, xem lỗi từng dòng, xác nhận một lần và đối chiếu tổng kết/email kích hoạt. |
| S2-02 | Sửa tên và số điện thoại Việt Nam; thử số sai; xác nhận hồ sơ không cho tự sửa username, vai trò, kho hoặc địa bàn. |
| S2-03 | Tải JPG/PNG tối đa 2 MB, kiểm tra ảnh vuông và thumbnail; thử tệp giả ảnh hoặc vượt giới hạn. |
| S2-04 | Tạo/sửa dữ liệu danh mục và giá rồi lọc audit theo người, đối tượng, thời gian; đối chiếu trước/sau. Admin không thấy giá vốn trong dữ liệu và nhật ký. |
| S2-05 | Tạo SKU, thử trùng mã, sửa trạng thái/quy cách; đăng nhập quản lý kinh doanh để sửa giá vốn. Sản phẩm có tham chiếu giao dịch phải được bảo vệ khỏi xóa. |
| S2-06 | Tạo cây nhóm ba cấp, chuyển sản phẩm; thử tạo vòng lặp hoặc xóa nhóm còn sản phẩm/nhóm con. |
| S2-07 | Khai báo lon/lốc/thùng, kiểm tra quy đổi; đổi hệ số và đối chiếu snapshot cũ bằng integration test. Tài khoản kho ngoài phạm vi bị từ chối. |
| S2-08 | Tải mẫu, nhập SKU mới và đã có; xem trước phải phân biệt tạo/cập nhật, báo lỗi từng dòng. Bài 5.000 SKU đã có trong integration test. |
| S2-09 | Tạo/sửa nhà cung cấp theo kho, thử mã trùng và dữ liệu sai; nhà cung cấp có tham chiếu phiếu nhập chỉ được ngừng giao dịch. |
| S2-10 | Tạo giá theo nhóm khách và ngày hiệu lực, thử khoảng ngày chồng lấn, giá sàn; tạo phiên bản mới từ bảng đã dùng. Khóa phiên bản đã dùng và điều kiện dưới sàn đã được kiểm tra ở service. |

## Giới hạn phạm vi

Luồng đặt đơn, ghi sổ nhập/xuất, hóa đơn, hạn mức công nợ và phê duyệt dưới giá sàn sẽ cần tích hợp ở sprint nghiệp vụ tiếp theo. Hiện có bảng tham chiếu, snapshot quy đổi/giá và kiểm tra cần duyệt ở service; chưa thể nghiệm thu toàn bộ các luồng đó qua giao diện. Các integration test tạo tham chiếu để kiểm tra ràng buộc danh mục.

Không xóa các nhánh lịch sử sau khi hợp nhất. Cập nhật `develop` bằng merge/fast-forward, không force-push hoặc viết lại lịch sử.
