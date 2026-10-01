<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Đổi mật khẩu | Quản lý bán hàng</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/app.css?v=sprint2-20261001">
    <script src="${pageContext.request.contextPath}/assets/js/login.js" defer></script>
</head>
<body class="app-page">
<%@ include file="../fragments/sidebar.jspf" %>
<div class="app-content">
    <header class="app-topbar"><div><p class="breadcrumb">Tài khoản</p><h1>Đổi mật khẩu</h1></div><a class="button button-secondary" href="${pageContext.request.contextPath}/dashboard">Quay lại</a></header>
    <main class="page-body narrow-body">
        <section class="content-panel account-panel">
            <div class="panel-heading"><div><h2>Cập nhật mật khẩu</h2><p>Sau khi đổi, các thiết bị khác đang đăng nhập sẽ được đăng xuất.</p></div></div>
            <c:if test="${param.required == 'true'}"><div class="alert alert-warning">Đây là lần đăng nhập đầu tiên. Bạn cần đổi mật khẩu tạm trước khi tiếp tục.</div></c:if>
            <c:if test="${not empty successMessage}"><div class="alert alert-success" role="status"><c:out value="${successMessage}"/></div></c:if>
            <c:if test="${not empty formError}"><div class="alert alert-error" role="alert"><c:out value="${formError}"/></div></c:if>
            <form class="form-stack account-form" method="post" action="${pageContext.request.contextPath}/account/change-password" novalidate>
                <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                <div class="form-field"><label for="current-password">Mật khẩu hiện tại</label><div class="password-control"><input id="current-password" name="currentPassword" type="password" maxlength="128" autocomplete="current-password" aria-invalid="${not empty currentPasswordError}"><button class="password-toggle" type="button" data-password-toggle="current-password">Hiện</button></div><p class="field-error"><c:out value="${currentPasswordError}"/></p></div>
                <div class="form-field"><label for="new-password">Mật khẩu mới</label><div class="password-control"><input id="new-password" name="newPassword" type="password" maxlength="72" autocomplete="new-password" aria-invalid="${not empty passwordError}"><button class="password-toggle" type="button" data-password-toggle="new-password">Hiện</button></div><p class="field-helper">Từ 8 đến 72 ký tự, gồm ít nhất một chữ cái và một chữ số.</p><p class="field-error"><c:out value="${passwordError}"/></p></div>
                <div class="form-field"><label for="confirmation">Nhập lại mật khẩu mới</label><div class="password-control"><input id="confirmation" name="confirmation" type="password" maxlength="72" autocomplete="new-password"><button class="password-toggle" type="button" data-password-toggle="confirmation">Hiện</button></div></div>
                <div class="form-actions"><button class="button button-primary" type="submit">Lưu thay đổi</button><a class="button button-plain" href="${pageContext.request.contextPath}/dashboard">Hủy</a></div>
            </form>
        </section>
    </main>
</div>
</body>
</html>

