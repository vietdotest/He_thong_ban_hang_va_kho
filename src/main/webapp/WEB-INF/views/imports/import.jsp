<%@ page contentType="text/html;charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title><c:out value="${title}"/></title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/app.css?v=s201-20261002">
</head>
<body class="app-page">
<%@ include file="../fragments/sidebar.jspf" %>
<main class="page-body app-content ${userImportPage ? 'user-import-page' : ''}">
    <h1><c:out value="${title}"/></h1>
    <section class="content-panel import-upload">
        <c:if test="${not userImportPage}">
            <p>XLSX tối đa 10MB và 10.000 dòng. SKU hiện có được cập nhật; SKU mới được tạo. Mã nhóm phải tồn tại. Trạng thái dùng ACTIVE (đang kinh doanh) hoặc DISCONTINUED (ngừng kinh doanh). Giá vốn để trống sẽ giữ giá cũ.</p>
        </c:if>
        <a class="button" href="?template=1" download>Tải tệp mẫu</a>
        <form method="post" enctype="multipart/form-data" class="import-upload-form">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <label class="field" for="import-file">Tệp XLSX (tối đa 10 MB)
                <input id="import-file" type="file" name="file" accept=".xlsx" required
                       aria-invalid="${not empty errors.file}" aria-describedby="import-file-error">
            </label>
            <c:if test="${not empty errors.file}">
                <p id="import-file-error" class="field-error" role="alert"><c:out value="${errors.file}"/></p>
            </c:if>
            <button class="button button-primary">Xem trước</button>
        </form>
    </section>
    <c:if test="${not empty errors.preview}">
        <div class="alert alert-error" role="alert"><c:out value="${errors.preview}"/></div>
    </c:if>
    <c:if test="${not empty report}">
        <div class="alert import-summary" role="status">Đã nhập: <strong><c:out value="${successCount}"/></strong>. Không nhập: <strong><c:out value="${failureCount}"/></strong>.</div>
    </c:if>
    <c:if test="${not empty preview}">
        <h2>Xem trước</h2>
        <c:choose>
            <c:when test="${userImportPage}">
                <p class="import-summary">Hợp lệ: <strong><c:out value="${validCount}"/></strong>. Có lỗi: <strong><c:out value="${invalidCount}"/></strong>.</p>
            </c:when>
            <c:otherwise><p>Chỉ nhập các dòng hợp lệ. Dữ liệu và quyền sẽ được kiểm tra lại khi xác nhận.</p></c:otherwise>
        </c:choose>
    </c:if>
    <c:if test="${not empty preview or not empty report}">
        <div class="table-wrap import-table" tabindex="0" role="region" aria-label="Kết quả nhập Excel">
            <table>
                <thead><tr><th scope="col">Dòng</th><th scope="col">Thao tác</th>
                    <c:forEach items="${headers}" var="h"><th scope="col"><c:out value="${h}"/></th></c:forEach>
                    <th scope="col">Kết quả</th>
                </tr></thead>
                <tbody>
                <c:forEach items="${not empty preview ? preview.lines : report}" var="line">
                    <tr class="${empty line.error ? 'import-row-valid' : 'import-row-error'}">
                        <td><c:out value="${line.number}"/></td><td><c:out value="${line.operation}"/></td>
                        <c:forEach items="${headers}" var="h" varStatus="i"><td><c:out value="${line.cells[i.index]}"/></td></c:forEach>
                        <td><c:choose><c:when test="${not empty line.error}"><c:out value="${line.error}"/></c:when>
                            <c:when test="${not empty report}">Đã nhập</c:when><c:otherwise>Hợp lệ</c:otherwise></c:choose></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
        </div>
    </c:if>
    <c:if test="${not empty preview}">
        <form method="post" class="import-confirm">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <input type="hidden" name="action" value="confirm">
            <input type="hidden" name="token" value="<c:out value='${preview.token}'/>">
            <button class="button button-primary" ${userImportPage and validCount == 0 ? 'disabled' : ''}>Xác nhận nhập dòng hợp lệ</button>
        </form>
    </c:if>
</main>
</body>
</html>

