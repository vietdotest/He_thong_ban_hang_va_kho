# Nghiệm thu bổ sung UI và nghiệp vụ sau MVP

Phạm vi: một đợt phát hành theo yêu cầu người dùng ngày 15/10/2026. Chứng cứ dưới đây là các ca phải đạt; không phải tuyên bố đã phát hành hoặc đã được người dùng duyệt ảnh mới.

| Yêu cầu | Hành vi cần nghiệm thu | Kiểm chứng |
| --- | --- | --- |
| Định dạng số | Tiền đồng nguyên HALF_UP; lượng/hệ số bỏ số 0 thừa; giá trị POST chính xác | QuantityDisplayTest, MoneyDisplayTest; mở/lưu lại nháp, báo giá, cổng và bản in trên Preview |
| Mã đơn | Dùng số DH đã lưu ở mọi màn, tìm theo số; nháp hiển thị Nháp #ID | BusinessFiltersAcceptanceIT; đối chiếu đơn, kho, hóa đơn và bản in |
| Đại lý/tín dụng | Hướng dẫn hiện hành; canDelete cùng chính sách POST, giữ quyền và lịch sử | DealerProfileAcceptanceIT; giao diện đại lý có/chưa có tham chiếu |
| S4-07 / SCRUM-53, phần hoãn SCRUM-246 | Đại lý, phụ trách hiện tại, khu vực, ngày tạo VN; tổng mọi trang loại nháp/hủy/từ chối | BusinessFiltersAcceptanceIT, OrderServletTest; URL, quay lại và phân trang |
| S5-05 / SCRUM-61 | Nhóm gồm nhóm con; còn/hết khả dụng, giữ nguyên phạm vi kho | BusinessFiltersAcceptanceIT; kho ngoài phạm vi, giữ hết và giữ hết hạn |
| S7-03 / SCRUM-77, phần hoãn SCRUM-255 | Sáu nhóm tuổi từ ngày đến hạn; tổng khớp nợ; Excel đúng quyền, ô tiền dạng số | DebtAgingTest, MvpDebtReportAcceptanceIT; tải XLSX thực tế và kiểm chi tiết nhóm |
| Phần biên lợi nhuận của S8-06 / SCRUM-89 | Chốt giá vốn khi xuất, tính lượng thực nhận, đảo giá vốn gốc cho trả tốt/hỏng; chỉ QLKD có COST_READ | CostSnapshotAcceptanceIT; dữ liệu cũ thiếu giá vốn, quyền, số lẻ, khác kỳ, replay, audit rollback |
| Phát hành | Nhánh chức năng → develop → main; Preview cô lập; migration chỉ thêm cột; rollback giữ DB/uploads | Maven clean verify, JspC, ảnh/axe/bàn phím; diễn tập lỗi health và rollback trên Preview |

## Đối chiếu Jira trực tiếp ngày 15/10/2026

SCRUM-53/61/77 hiện mô tả AC đã thu gọn cho MVP. SCRUM-246 và SCRUM-255 bảo toàn phần bộ lọc nâng cao và tuổi nợ/Excel chưa hoàn thành. Vì vậy các thiếu sót này không phải bằng chứng phải mở lại toàn bộ MVP.

SCRUM-89 đầy đủ yêu cầu nhóm theo nhân viên/khu vực/đại lý/nhóm hàng/SKU, doanh số/sản lượng/biên, so sánh kỳ trước và Excel. Đợt hiện tại bổ sung riêng biên lợi nhuận theo kế hoạch đã chốt; không đánh dấu hoàn thành toàn bộ SCRUM-89 hoặc các subtask chỉ từ thay đổi này.

## Quy tắc dữ liệu và nghiệm thu

- Dữ liệu cũ không được lấy giá vốn danh mục hiện tại điền ngược. Thiếu giá vốn ở bất kỳ dòng liên quan nào thì không công bố lãi gộp/biên toàn nhóm hoặc toàn kỳ như dữ liệu đầy đủ.
- Trả tốt/hỏng đều đảo giá vốn theo chứng từ gốc vào ngày phát hành giảm trừ. Chỉ tiêu là lãi gộp bán hàng, chưa tính tổn thất hàng hỏng và chi phí vận hành.
- Fixture và các ca ghi nghiệp vụ chỉ chạy trên Preview/database tạm. Public chỉ kiểm đọc; không dùng dữ liệu nghiệp vụ thật để thử xóa hoặc diễn tập lỗi.
- Kiểm 360/768/1024/1100/1440px, bàn phím và axe. Ảnh ứng viên lưu riêng; không cập nhật ảnh chuẩn trước khi người dùng duyệt.
- Chỉ phát hành Public sau khi nghiệm thu Preview và diễn tập WAR cũ trên schema mới đạt. Migration V044 nullable không đòi hỏi phục hồi SQL khi lùi WAR; không tự lùi DB/uploads.
