<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Quên mật khẩu | Quản lý bán hàng</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/app.css?v=sprint2-20261001">
</head>
<body class="auth-page">
<header class="auth-topbar"><a class="wordmark" href="${pageContext.request.contextPath}/login"><span class="wordmark-symbol">BH</span><span>Quản lý bán hàng</span></a></header>
<main class="auth-main">
    <section class="auth-card" aria-labelledby="page-title">
        <c:choose>
            <c:when test="${requestSent}">
                <header class="auth-heading"><h1 id="page-title">Kiểm tra email của bạn</h1><p>Nếu thông tin vừa nhập khớp với một tài khoản, chúng tôi đã gửi liên kết đặt lại mật khẩu.</p></header>
                <div class="alert alert-info" role="status">Liên kết có hiệu lực trong 30 phút và chỉ dùng được một lần.</div>
                <a class="button button-primary button-block" href="${pageContext.request.contextPath}/login">Quay lại đăng nhập</a>
            </c:when>
            <c:otherwise>
                <header class="auth-heading"><h1 id="page-title">Quên mật khẩu</h1><p>Nhập tên đăng nhập hoặc email đã đăng ký. Chúng tôi sẽ gửi hướng dẫn nếu tìm thấy tài khoản phù hợp.</p></header>
                <c:if test="${not empty formError}"><div class="alert alert-error" role="alert"><c:out value="${formError}"/></div></c:if>
                <form class="form-stack" method="post" action="${pageContext.request.contextPath}/forgot-password" novalidate>
                    <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                    <div class="form-field">
                        <label for="identity">Tên đăng nhập hoặc email</label>
                        <input id="identity" name="identity" type="text" maxlength="254" autocomplete="username"
                               value="<c:out value='${identity}'/>" aria-invalid="${not empty identityError}"
                               aria-describedby="identity-error" autofocus>
                        <p id="identity-error" class="field-error"><c:out value="${identityError}"/></p>
                    </div>
                    <button class="button button-primary button-block" type="submit">Gửi hướng dẫn</button>
                </form>
                <a class="back-link" href="${pageContext.request.contextPath}/login">← Quay lại đăng nhập</a>
            </c:otherwise>
        </c:choose>
    </section>
</main>
<footer class="auth-bottom">Hệ thống quản lý bán hàng và kho</footer>
</body>
</html>

