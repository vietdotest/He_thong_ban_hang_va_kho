<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="vi"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Nhóm hàng</title><%@ include file="../fragments/assets.jspf" %></head>
<body class="app-page"><%@ include file="../fragments/sidebar.jspf" %>
<%@ include file="../fragments/topbar.jspf" %>
<main class="page-body app-content catalog-page"><h1>Nhóm hàng nhiều cấp</h1>
<c:if test="${param.notice == 'saved'}"><p class="alert" role="status">Đã lưu thay đổi nhóm hàng.</p></c:if>
<c:if test="${access.allows('PRODUCT_MANAGE')}">
<section class="content-panel"><h2>${empty edit.id ? 'Thêm nhóm' : 'Sửa nhóm'}</h2>
<form method="post" class="account-form">
<input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
<input type="hidden" name="id" value="<c:out value='${edit.id}'/>"><input type="hidden" name="version" value="<c:out value='${edit.version}'/>">
<c:if test="${not empty errors.form}"><p class="field-error" role="alert"><c:out value="${errors.form}"/></p></c:if>
<label class="field">Mã nhóm <input name="code" required maxlength="50" value="<c:out value='${edit.code}'/>" aria-invalid="${not empty errors.code}" aria-describedby="category-code-error"><small id="category-code-error" class="field-error"><c:out value="${errors.code}"/></small></label>
<label class="field">Tên nhóm <input name="name" required minlength="2" maxlength="150" value="<c:out value='${edit.name}'/>" aria-invalid="${not empty errors.name}" aria-describedby="category-name-error"><small id="category-name-error" class="field-error"><c:out value="${errors.name}"/></small></label>
<label class="field">Nhóm cha <select name="parent" aria-invalid="${not empty errors.parent}" aria-describedby="category-parent-error"><option value="">Nhóm gốc</option>
<c:if test="${not empty edit.parent_id}"><option value="<c:out value='${edit.parent_id}'/>" selected>Nhóm #<c:out value="${edit.parent_id}"/></option></c:if>
<c:forEach items="${categories}" var="cat"><option value="${cat.id}" ${not empty edit.parent_id and cat.id.toString() == edit.parent_id.toString() ? 'selected' : ''}><c:out value="${cat.label}"/></option></c:forEach></select><small id="category-parent-error" class="field-error"><c:out value="${errors.parent}"/></small></label>
<button class="button button-primary">Lưu nhóm</button></form></section></c:if>
<div class="table-wrap"><table><thead><tr><th>Mã nhóm</th><th>Cây nhóm hàng</th><th>Thao tác</th></tr></thead><tbody>
<c:forEach items="${categories}" var="cat"><tr><td><c:out value="${cat.code}"/></td><td><c:out value="${cat.label}"/></td><td><c:if test="${access.allows('PRODUCT_MANAGE')}"><a href="?id=${cat.id}">Sửa</a><form method="post"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="id" value="${cat.id}"><input type="hidden" name="action" value="delete"><button class="button">Xóa nhóm trống</button></form></c:if></td></tr></c:forEach>
</tbody></table></div></main></body></html>

