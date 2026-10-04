<%@ page contentType="text/html;charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="vi"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Kho và địa bàn</title><%@ include file="../fragments/assets.jspf" %></head><body class="app-page"><%@ include file="../fragments/sidebar.jspf" %>
<%@ include file="../fragments/topbar.jspf" %>
<main class="page-body app-content scopes-page">
<div class="page-heading"><div><h1>Kho và địa bàn</h1><p>Kho lưu địa chỉ nơi chứa hàng. Địa bàn xác định khu vực phụ trách kinh doanh.</p></div><div class="page-actions"><a class="button button-primary" href="?new=1&amp;kind=warehouse#scope-editor" data-editor="scope-editor" data-scope-kind="warehouse">＋ Thêm kho</a><a class="button button-secondary" href="?new=1&amp;kind=territory#scope-editor" data-editor="scope-editor" data-scope-kind="territory">＋ Thêm địa bàn</a></div></div>
<div class="alert"><strong>Tạo danh mục rồi phân công cho người dùng</strong>Tạo kho hoặc địa bàn chưa tự gán cho ai. Vào <a href="${pageContext.request.contextPath}/admin/users">Người dùng</a> → Phân công để chọn vai trò và phạm vi phụ trách.</div>
<c:if test="${param.notice == 'saved'}"><div class="alert alert-success" role="status">Đã lưu kho / địa bàn.</div></c:if>
<c:if test="${not empty formError}"><div class="alert alert-error" role="alert"><c:out value="${formError}"/></div></c:if>
<details class="content-panel disclosure editor" id="scope-editor" ${not empty formError or param['new'] == '1' or not empty form.id ? 'open' : ''}>
<summary>${form.id > 0 ? 'Sửa kho / địa bàn' : 'Thêm kho / địa bàn'}</summary>
<form class="account-form form-grid" method="post" action="${pageContext.request.contextPath}/admin/scopes">
<input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="id" value="<c:out value='${form.id}'/>"><input type="hidden" name="version" value="<c:out value='${form.version}'/>">
<label class="field">Loại <select name="kind" ${form.id > 0 ? 'disabled' : ''}><option value="warehouse">Kho</option><option value="territory" ${form.kind == 'territory' ? 'selected' : ''}>Địa bàn</option></select></label><c:if test="${form.id > 0}"><input type="hidden" name="kind" value="<c:out value='${form.kind}'/>"></c:if>
<label class="field">Mã <input name="code" required maxlength="50" value="<c:out value='${form.code}'/>" aria-invalid="${not empty errors.code}" aria-describedby="scope-code-error"><small class="field-error" id="scope-code-error"><c:out value="${errors.code}"/></small></label>
<label class="field form-full">Tên <input name="name" required maxlength="150" value="<c:out value='${form.name}'/>" aria-invalid="${not empty errors.name}" aria-describedby="scope-name-error"><small class="field-error" id="scope-name-error"><c:out value="${errors.name}"/></small></label>
<label class="field form-full">Địa chỉ / khu vực phụ trách <textarea name="address" rows="3" required maxlength="500" aria-invalid="${not empty errors.address}" aria-describedby="scope-address-help scope-address-error"><c:out value="${form.address}"/></textarea><small class="muted" id="scope-address-help">Kho: số nhà, đường, phường/xã, tỉnh/thành. Địa bàn: ghi rõ khu vực kinh doanh được giao. Tối đa 500 ký tự.</small><small class="field-error" id="scope-address-error"><c:out value="${errors.address}"/></small></label>
<div class="form-actions form-full"><button class="button button-primary">Lưu kho / địa bàn</button><a class="button button-secondary" href="${pageContext.request.contextPath}/admin/scopes">Hủy</a></div>
</form></details>
<c:forTokens var="kind" items="warehouse,territory" delims=",">
<c:set var="scopeRows" value="${kind == 'warehouse' ? warehouses : territories}"/>
<section class="content-panel"><div class="panel-heading"><h2>${kind == 'warehouse' ? 'Kho' : 'Địa bàn'}</h2></div>
<div class="table-wrap"><table class="data-table scope-table"><thead><tr><th>Mã</th><th>Tên</th><th>Địa chỉ / khu vực</th><th>Người được gán</th><th>Thao tác</th></tr></thead><tbody>
<c:forEach var="scope" items="${scopeRows}"><tr><td><c:out value="${scope.code}"/></td><td><c:out value="${scope.name}"/></td><td><c:choose><c:when test="${not empty scope.address}"><c:out value="${scope.address}"/></c:when><c:otherwise><span class="muted">Chưa bổ sung địa chỉ</span></c:otherwise></c:choose></td><td><c:out value="${scope.assigned_count}"/></td><td><c:url var="editScope" value="/admin/scopes"><c:param name="kind" value="${kind}"/><c:param name="edit" value="${scope.id}"/></c:url><a href="${editScope}#scope-editor">Sửa</a></td></tr></c:forEach>
<c:if test="${empty scopeRows}"><tr><td colspan="5" class="empty-state">${kind == 'warehouse' ? 'Chưa có kho. Thêm kho kèm địa chỉ trước khi gán vai trò kho.' : 'Chưa có địa bàn. Thêm khu vực phụ trách khi cần phân công kinh doanh.'}</td></tr></c:if>
</tbody></table></div></section>
</c:forTokens>
<a class="button button-secondary" href="${pageContext.request.contextPath}/admin/users">Phân công người dùng</a>
</main></body></html>
