<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Nhật ký thao tác</title>
    <%@ include file="../fragments/assets.jspf" %>
</head>
<body class="app-page audit-page">
<%@ include file="../fragments/sidebar.jspf" %>
<%@ include file="../fragments/topbar.jspf" %>
<div class="app-content">

    <main class="page-body">
<div class="page-heading"><div><h1><c:out value="${uiTitle}"/></h1></div></div>

        <section class="content-panel audit-filter-panel" aria-labelledby="audit-filter-heading">
            <h2 id="audit-filter-heading">Bộ lọc nhật ký</h2>
            <p class="audit-helper">Tra cứu người thực hiện và đối chiếu dữ liệu trước, sau thay đổi.</p>
            <form method="get" action="${pageContext.request.contextPath}/admin/audit" class="audit-filters">
                <c:if test="${not empty filterError}"><p class="field-error" role="alert"><c:out value="${filterError}"/></p></c:if>
                <label class="field">Người thực hiện
                    <select name="userId">
                        <option value="">Tất cả</option>
                        <c:forEach var="u" items="${users}">
                            <option value="${u.id}" ${param.userId == u.id.toString() ? 'selected' : ''}><c:out value="${u.full_name}"/></option>
                        </c:forEach>
                    </select>
                </label>
                <label class="field">Loại đối tượng
                    <select name="type">
                        <option value="">Tất cả</option>
                        <c:forEach var="t" items="${types}">
                            <option value="<c:out value='${t.object_type}'/>" ${param.type == t.object_type ? 'selected' : ''}><c:out value="${t.object_label}"/></option>
                        </c:forEach>
                    </select>
                </label>
                <label class="field">Từ ngày <input type="date" name="from" value="<c:out value='${param.from}'/>"></label>
                <label class="field">Đến ngày <input type="date" name="to" value="<c:out value='${param.to}'/>"></label>
                <label class="field">Trang <input type="number" name="page" min="1" max="100000" value="${pageNumber}"></label>
                <div class="audit-filter-actions">
                    <button class="button button-primary" type="submit">Lọc nhật ký</button>
                    <a class="button button-secondary" href="${pageContext.request.contextPath}/admin/audit">Xóa bộ lọc</a>
                </div>
            </form>
        </section>
        <section class="content-panel audit-results" aria-label="Kết quả nhật ký">
            <div class="audit-results-heading">
                <h2>Lịch sử thao tác</h2>
                <p>Trang <strong><c:out value="${pageNumber}"/></strong> · <strong><c:out value="${fn:length(logs)}"/></strong> bản ghi đang hiển thị</p>
            </div>
            <c:if test="${empty logs}">
                <p class="audit-empty">Không có nhật ký phù hợp. Hãy thay đổi hoặc xóa bộ lọc.</p>
            </c:if>
            <c:if test="${not empty logs}">
            <div class="table-wrap">
                <table class="data-table audit-table">
                    <thead>
                    <tr><th scope="col">Thời điểm</th><th scope="col">Người thực hiện</th><th scope="col">Thao tác</th><th scope="col">Đối tượng</th><th scope="col">Trước thay đổi</th><th scope="col">Sau thay đổi</th></tr>
                    </thead>
                    <tbody>
                    <c:forEach var="log" items="${logs}">
                        <tr>
                            <td class="audit-time"><c:out value="${log.display_time}"/></td>
                            <td><c:out value="${log.full_name}"/></td>
                            <td><c:out value="${log.event_label}"/></td>
                            <td><c:out value="${log.object_label}"/><c:if test="${not empty log.object_id}"> #<c:out value="${log.object_id}"/></c:if></td>
                            <td><pre><c:out value="${log.before_values}"/></pre><c:if test="${canCost}"><pre><c:out value="${log.before_cost}"/></pre></c:if></td>
                            <td><pre><c:out value="${log.after_values}"/></pre><c:if test="${canCost}"><pre><c:out value="${log.after_cost}"/></pre></c:if></td>
                        </tr>
                    </c:forEach>
                    </tbody>
                </table>
            </div>
            </c:if>
        </section>
<nav class="pagination" aria-label="Phân trang nhật ký"><c:if test="${pageNumber > 1}"><c:url var="prev" value="/admin/audit"><c:param name="page" value="${pageNumber-1}"/><c:param name="userId" value="${param.userId}"/><c:param name="type" value="${param.type}"/><c:param name="from" value="${param.from}"/><c:param name="to" value="${param.to}"/></c:url><a class="button button-secondary" href="<c:out value='${prev}'/>">Trước</a></c:if><c:if test="${hasNext}"><c:url var="next" value="/admin/audit"><c:param name="page" value="${pageNumber+1}"/><c:param name="userId" value="${param.userId}"/><c:param name="type" value="${param.type}"/><c:param name="from" value="${param.from}"/><c:param name="to" value="${param.to}"/></c:url><a class="button button-secondary" href="<c:out value='${next}'/>">Tiếp</a></c:if></nav>
    </main>
</div>
</body>
</html>
