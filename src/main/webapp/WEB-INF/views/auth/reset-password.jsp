<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Đặt lại mật khẩu | Quản lý bán hàng</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/app.css">
    <script src="${pageContext.request.contextPath}/assets/js/login.js" defer></script>
</head>
<body class="auth-page">
<header class="auth-topbar"><a class="wordmark" href="${pageContext.request.contextPath}/login"><span class="wordmark-symbol">BH</span><span>Quản lý bán hàng</span></a></header>
<main class="auth-main">
    <section class="auth-card" aria-labelledby="page-title">
        <c:choose>
            <c:when test="${tokenValid}">
                <header class="auth-heading"><h1 id="page-title">Tạo mật khẩu mới</h1><p>Mật khẩu cần có ít nhất 8 ký tự, gồm chữ cái và chữ số.</p></header>
                <c:if test="${not empty formError}"><div class="alert alert-error" role="alert"><c:out value="${formError}"/></div></c:if>
                <form class="form-stack" method="post" action="${pageContext.request.contextPath}/reset-password" novalidate>
                    <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                    <input type="hidden" name="token" value="<c:out value='${token}'/>">
                    <div class="form-field">
                        <label for="new-password">Mật khẩu mới</label>
                        <div class="password-control"><input id="new-password" name="newPassword" type="password" maxlength="72" autocomplete="new-password" aria-invalid="${not empty passwordError}"><button class="password-toggle" type="button" data-password-toggle="new-password">Hiện</button></div>
                        <p class="field-error"><c:out value="${passwordError}"/></p>
                    </div>
                    <div class="form-field">
                        <label for="confirmation">Nhập lại mật khẩu mới</label>
                        <div class="password-control"><input id="confirmation" name="confirmation" type="password" maxlength="72" autocomplete="new-password"><button class="password-toggle" type="button" data-password-toggle="confirmation">Hiện</button></div>
                    </div>
                    <button class="button button-primary button-block" type="submit">Cập nhật mật khẩu</button>
                </form>
            </c:when>
            <c:otherwise>
                <header class="auth-heading"><h1 id="page-title">Liên kết không còn hiệu lực</h1><p>Liên kết có thể đã hết hạn hoặc đã được sử dụng.</p></header>
                <a class="button button-primary button-block" href="${pageContext.request.contextPath}/forgot-password">Gửi yêu cầu mới</a>
                <a class="back-link" href="${pageContext.request.contextPath}/login">← Quay lại đăng nhập</a>
            </c:otherwise>
        </c:choose>
    </section>
</main>
<footer class="auth-bottom">Hệ thống quản lý bán hàng và kho</footer>
</body>
</html>
