<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="vi">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title><c:out value="${title}"/> | Quản lý bán hàng</title>
    <%@ include file="../fragments/assets.jspf" %>
</head>
<body class="app-page">
<%@ include file="../fragments/sidebar.jspf" %>
<%@ include file="../fragments/topbar.jspf" %>
<main class="page-body app-content import-page">
    <c:set var="listPath" value="${userImportPage ? '/admin/users' : '/catalog/products'}"/>
    <div class="page-heading"><div>
        <h1><c:choose><c:when test="${importStep == 2}">${userImportPage ? 'Kiểm tra dữ liệu trước khi nhập' : 'Kiểm tra danh mục sản phẩm'}</c:when><c:when test="${importStep == 3}">${userImportPage ? 'Kết quả nhập người dùng' : 'Kết quả nhập sản phẩm'}</c:when><c:otherwise>${userImportPage ? 'Nhập người dùng từ Excel' : 'Nhập sản phẩm từ Excel'}</c:otherwise></c:choose></h1>
        <p><c:choose><c:when test="${importStep > 1}"><c:out value="${filename}"/><c:if test="${importStep == 2}"> · <c:out value="${rowCount}"/> dòng dữ liệu</c:if></c:when><c:otherwise>Chọn tệp dữ liệu, kiểm tra từng dòng trước khi nhập.</c:otherwise></c:choose></p>
    </div><c:if test="${importStep == 2}"><a class="button button-secondary" href="?new=1"><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/upload-outline.svg">Chọn tệp khác</a></c:if></div>
    <ol class="import-steps" aria-label="Tiến trình nhập Excel">
        <li class="${importStep == 1 ? 'current' : 'complete'}" aria-current="${importStep == 1 ? 'step' : 'false'}"><span class="step-number">1</span><div><strong>Chọn tệp</strong><small>${importStep == 1 ? 'Đang thực hiện' : 'Đã xong'}</small></div></li>
        <li class="${importStep == 2 ? 'current' : importStep == 3 ? 'complete' : ''}" aria-current="${importStep == 2 ? 'step' : 'false'}"><span class="step-number">2</span><div><strong>Kiểm tra dữ liệu</strong><small>${importStep == 2 ? 'Đang thực hiện' : importStep == 3 ? 'Đã xong' : 'Chưa thực hiện'}</small></div></li>
        <li class="${importStep == 3 ? 'complete current' : ''}" aria-current="${importStep == 3 ? 'step' : 'false'}"><span class="step-number">3</span><div><strong>Hoàn tất</strong><small>${importStep == 3 ? 'Đã hoàn tất' : 'Chưa thực hiện'}</small></div></li>
    </ol>
    <c:if test="${not empty errors.preview}"><div class="alert alert-error" role="alert"><c:out value="${errors.preview}"/></div></c:if>
    <c:if test="${importStep == 1}">
        <form method="post" enctype="multipart/form-data" class="import-upload-form">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <div class="import-upload-layout">
                <section class="content-panel import-upload">
                    <img class="icon upload-mark" alt="" src="${pageContext.request.contextPath}/assets/icons/upload-outline.svg">
                    <h2>Chọn tệp danh sách ${userImportPage ? 'người dùng' : 'sản phẩm'}</h2>
                    <p class="muted">Tệp XLSX, tối đa 10 MB và 10.000 dòng. Dùng đúng các cột trong tệp mẫu.</p>
                    <label class="button button-primary file-button" for="import-file"><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/upload.svg">Chọn tệp XLSX
                        <input class="file-input" id="import-file" type="file" name="file" accept=".xlsx" required data-import-file aria-invalid="${not empty errors.file}" aria-describedby="import-file-name import-file-error">
                    </label>
                    <p id="import-file-name" class="muted" data-file-name>Sau khi chọn tệp, bạn có thể kiểm tra từng dòng trước khi nhập.</p>
                    <p id="import-file-error" class="field-error" role="alert"><c:out value="${errors.file}"/></p>
                </section>
                <aside class="content-panel import-guide"><h2>Trước khi nhập</h2><ol><li>Tải tệp mẫu và giữ nguyên tên cột.</li><li>Điền dữ liệu theo từng dòng.</li><li>Kiểm tra các dòng lỗi ở bước tiếp theo.</li></ol>
                    <a class="button button-secondary" href="?template=1" download><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/download.svg">Tải tệp mẫu</a>
                    <p class="muted"><c:choose><c:when test="${userImportPage}">Dòng hợp lệ được tạo tài khoản và gửi email kích hoạt. Dòng lỗi được bỏ qua và ghi vào báo cáo.</c:when><c:otherwise>SKU hiện có được cập nhật, SKU mới được tạo. Mã nhóm phải tồn tại. Giá vốn để trống giữ giá cũ; cột giá vốn chỉ có khi được cấp quyền.</c:otherwise></c:choose></p>
                </aside>
            </div>
            <div class="import-footer"><a class="button button-plain" href="${pageContext.request.contextPath}${listPath}">Quay lại ${userImportPage ? 'người dùng' : 'sản phẩm'}</a><button class="button button-primary" data-preview-button disabled>Xem trước</button></div>
        </form>
    </c:if>
    <c:if test="${importStep == 2}">
        <div class="alert ${invalidCount > 0 ? 'alert-warning' : 'alert-success'}" role="status"><strong>${invalidCount > 0 ? invalidCount : validCount} dòng ${invalidCount > 0 ? 'cần kiểm tra' : 'hợp lệ'}</strong>
            Có thể chọn lại tệp hoặc nhập <c:out value="${validCount}"/> dòng hợp lệ. Dòng lỗi sẽ được bỏ qua. Dữ liệu và quyền được kiểm tra lại khi xác nhận.
        </div>
    </c:if>
    <c:if test="${importStep == 3}">
        <section class="content-panel import-result-summary" aria-label="Kết quả nhập">
            <h2>Đã ${userImportPage ? 'nhập' : 'xử lý'} <c:out value="${successCount}"/> ${userImportPage ? 'người dùng' : 'dòng hợp lệ'}</h2>
            <p class="muted"><c:out value="${createdCount}"/> tạo mới<c:if test="${not userImportPage}"> · <c:out value="${updatedCount}"/> cập nhật</c:if> · <c:out value="${failureCount}"/> bỏ qua.</p>
            <div class="page-actions"><a class="button button-secondary" href="?report=1" download><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/download.svg">Tải báo cáo tổng kết</a><a class="button button-primary" href="${pageContext.request.contextPath}${listPath}">Xem danh sách ${userImportPage ? 'người dùng' : 'sản phẩm'}</a></div>
        </section>
    </c:if>
    <c:if test="${importStep > 1}">
        <div class="section-heading import-table-toolbar"><div class="import-counts"><span class="badge badge-success">${importStep == 2 ? validCount : successCount} ${importStep == 2 ? 'dòng hợp lệ' : 'đã nhập'}</span><span class="badge badge-danger">${importStep == 2 ? invalidCount : failureCount} ${importStep == 2 ? 'dòng lỗi' : 'bỏ qua'}</span></div>
            <label class="import-status-filter">Xem trạng thái <select data-import-status><option value="all">Tất cả dòng</option><option value="error" ${userImportPage and importStep == 3 ? 'selected' : ''}>Chỉ dòng lỗi</option><option value="valid">Dòng hợp lệ</option></select></label>
        </div>
        <div class="table-wrap import-table" role="region" aria-label="Kiểm tra dữ liệu nhập Excel">
            <table class="data-table"><thead><tr><th scope="col">Dòng</th><th scope="col">${userImportPage ? 'Tên đăng nhập' : 'SKU'}</th><th scope="col">${userImportPage ? 'Vai trò' : 'Tên sản phẩm'}</th><th scope="col">${userImportPage ? 'Kết quả' : 'Thao tác'}</th><th scope="col">Thông báo</th></tr></thead>
                <tbody><c:forEach items="${importStep == 2 ? preview.lines : report}" var="line"><tr class="${empty line.error ? 'import-row-valid' : 'import-row-error'}" data-import-row="${empty line.error ? 'valid' : 'error'}">
                    <td><c:out value="${line.number}"/></td><td><c:out value="${empty line.cells[0] ? '—' : line.cells[0]}"/></td>
                    <td><c:choose><c:when test="${userImportPage}"><c:out value="${importRoleLabels[line.number]}"/></c:when><c:otherwise><c:out value="${line.cells[1]}"/></c:otherwise></c:choose></td>
                    <td><span class="badge ${not empty line.error ? 'badge-danger' : 'badge-success'}"><c:choose><c:when test="${not empty line.error}">Bỏ qua</c:when><c:when test="${userImportPage}">${importStep == 2 ? 'Hợp lệ' : 'Đã tạo mới'}</c:when><c:otherwise>${importStep == 3 ? 'Đã ' : ''}<c:out value="${line.operation}"/></c:otherwise></c:choose></span></td>
                    <td><c:choose><c:when test="${not empty line.error}"><c:out value="${line.error}"/></c:when><c:otherwise>${importStep == 2 ? 'Sẵn sàng nhập' : 'Đã lưu dữ liệu'}</c:otherwise></c:choose>
                        <c:if test="${importStep == 2}"><details class="row-details"><summary>Thông tin dòng</summary><dl><c:forEach var="header" items="${headers}" varStatus="column"><div><dt><c:out value="${header}"/></dt><dd><c:out value="${line.cells[column.index]}"/></dd></div></c:forEach></dl></details></c:if>
                    </td>
                </tr></c:forEach><tr data-import-empty hidden><td colspan="5" class="empty-state">Không có dòng thuộc trạng thái này.</td></tr></tbody>
            </table>
        </div>
    </c:if>
    <c:if test="${importStep == 2}">
        <form method="post" class="import-footer import-confirm"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="action" value="confirm"><input type="hidden" name="token" value="<c:out value='${preview.token}'/>">
            <p class="muted"><c:out value="${createdCount}"/> dòng tạo mới<c:if test="${not userImportPage}"> · <c:out value="${updatedCount}"/> cập nhật</c:if> · <c:out value="${invalidCount}"/> bỏ qua</p>
            <div class="page-actions"><a class="button button-secondary" href="?new=1">Quay lại</a><button class="button button-primary" ${validCount == 0 ? 'disabled' : ''}>Nhập <c:out value="${validCount}"/> dòng hợp lệ</button></div>
        </form>
    </c:if>
    <c:if test="${importStep == 3}"><a class="button button-plain" href="?new=1"><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/upload-outline.svg">Nhập thêm tệp khác</a></c:if>
</main></body></html>
