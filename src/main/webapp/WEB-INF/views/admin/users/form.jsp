<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${editing ? 'Chỉnh sửa' : 'Tạo'} tài khoản | Quản lý bán hàng</title>
    <%@ include file="../../fragments/assets.jspf" %>
</head>
<body class="app-page">
<%@ include file="../../fragments/sidebar.jspf" %>
<%@ include file="../../fragments/topbar.jspf" %>

<div class="app-content">

    <main class="page-body narrow-body">
<div class="page-heading"><div><h1>${editing ? 'Thông tin người dùng' : 'Thêm người dùng'}</h1><p>Quản lý thông tin liên hệ và phân công tài khoản.</p></div><c:if test="${editing}"><div class="page-actions"><a class="button button-secondary" href="${pageContext.request.contextPath}/admin/assignments?id=${userId}">Phân công</a><a class="button button-secondary" href="${pageContext.request.contextPath}/admin/users/status?id=${userId}">Khóa / mở khóa</a></div></c:if></div>

        <section class="content-panel account-panel">
            <div class="panel-heading"><div><h2>Thông tin tài khoản</h2><p>${editing ? 'Cập nhật thông tin, vai trò và trạng thái sử dụng.' : 'Mật khẩu tạm sẽ được gửi đến email sau khi tạo thành công.'}</p></div></div>
            <c:if test="${not empty formError}"><div class="alert alert-error"><c:out value="${formError}"/></div></c:if>
            <form class="account-form user-form" method="post" action="${pageContext.request.contextPath}${editing ? '/admin/users/edit' : '/admin/users/new'}" novalidate>
                <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                <c:if test="${editing}"><input type="hidden" name="id" value="<c:out value='${userId}'/>"><input type="hidden" name="version" value="<c:out value='${form.version()}'/>"></c:if>

                <div class="form-grid">
                    <label class="field"><span>Họ và tên <b>*</b></span><input name="fullName" maxlength="150" autocomplete="name" value="<c:out value='${form.fullName()}'/>" required><c:if test="${not empty errors.fullName}"><small class="field-error"><c:out value="${errors.fullName}"/></small></c:if></label>
                    <label class="field"><span>Tên đăng nhập <b>*</b></span><input name="username" maxlength="64" autocomplete="username" value="<c:out value='${form.username()}'/>" required><c:if test="${not empty errors.username}"><small class="field-error"><c:out value="${errors.username}"/></small></c:if></label>
                    <label class="field"><span>Email <b>*</b></span><input type="email" name="email" maxlength="254" autocomplete="email" value="<c:out value='${form.email()}'/>" required><c:if test="${not empty errors.email}"><small class="field-error"><c:out value="${errors.email}"/></small></c:if></label>
                    <label class="field"><span>Số điện thoại <b>*</b></span><input type="tel" name="phone" maxlength="20" autocomplete="tel" placeholder="0901234567" value="<c:out value='${form.phone()}'/>" required><c:if test="${not empty errors.phone}"><small class="field-error"><c:out value="${errors.phone}"/></small></c:if></label>
                    <label class="field"><span>Vai trò <b>*</b></span><c:if test="${editing}"><a href="${pageContext.request.contextPath}/admin/assignments?id=${userId}">Gán nhiều vai trò và phạm vi</a></c:if><select name="roleCode" ${editing ? 'disabled' : ''} required>
                        <c:forEach var="role" items="${roles}">
                            <c:choose>
                                <c:when test="${form.roleCode() == role.code()}"><option value="${role.code()}" selected><c:out value="${role.name()}"/></option></c:when>
                                <c:otherwise><option value="${role.code()}"><c:out value="${role.name()}"/></option></c:otherwise>
                            </c:choose>
                        </c:forEach>
                    </select><c:if test="${not empty errors.roleCode}"><small class="field-error"><c:out value="${errors.roleCode}"/></small></c:if></label>
                    <p class="muted">Khóa/mở tài khoản được thực hiện riêng từ danh sách người dùng, kèm lý do khóa.</p>
                </div>

                <div class="form-actions">
                    <button class="button button-primary" type="submit">${editing ? 'Lưu thay đổi' : 'Tạo và gửi email'}</button>
                    <a class="button button-secondary" href="${pageContext.request.contextPath}/admin/users">Hủy</a>
                </div>
            </form>
        </section>
        <c:if test="${editing and form.status().name() == 'PENDING_ACTIVATION'}"><form method="post" class="content-panel account-form" action="${pageContext.request.contextPath}/admin/users/activation"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="id" value="${userId}"><p class="muted">Tài khoản đang chờ người dùng kích hoạt qua email.</p><button class="button button-secondary">Gửi lại email kích hoạt</button></form></c:if>
    </main>
</div>
</body>
</html>
