<%@ page contentType="text/html;charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<layout:page title="Phân quyền theo vai trò" view="admin/roles" bodyClass="app-page" css="" bare="false">
<jsp:body>
<main class="page-body app-content"><h1>Phân quyền theo vai trò</h1><a href="${pageContext.request.contextPath}/dashboard">Về tổng quan</a>
<c:if test="${param.notice == 'saved' and empty errors}"><div class="alert alert-success" role="status">Đã lưu phân quyền.</div></c:if><c:if test="${not empty formError}"><div class="alert alert-error" role="alert"><c:out value="${formError}"/></div></c:if><c:forEach var="role" items="${roles}"><details class="content-panel disclosure" ${errorRole == role.id.toString() ? 'open' : ''}><summary><c:out value="${role.name}"/></summary><form method="post" class="account-form permission-grid"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="roleId" value="${role.id}">
<c:forEach var="p" items="${permissions}"><c:set var="checked" value="false"/><c:forEach var="g" items="${grants}"><c:if test="${g.role_id == role.id and g.permission_id == p.id}"><c:set var="checked" value="true"/></c:if></c:forEach>
<label class="checkbox-field"><input type="checkbox" name="permission" value="${p.code}" ${(errorRole == role.id.toString() ? chosenPermissions.contains(p.code) : checked) ? 'checked' : ''}> <c:out value="${p.name}"/></label></c:forEach><ui:button variant="primary">Lưu quyền</ui:button></form></details></c:forEach></main>
</jsp:body>
</layout:page>
