<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<layout:page title="Đăng nhập" view="auth/login" bodyClass="auth-page" css="" bare="true">
<jsp:attribute name="head"><meta name="description" content="Đăng nhập hệ thống quản lý bán hàng và kho">


    <script src="${pageContext.request.contextPath}/assets/js/login.js?v=${applicationScope.assetVersion}" defer></script></jsp:attribute>
<jsp:body>
<header class="auth-topbar">
    <a class="wordmark" href="${pageContext.request.contextPath}/login">
        <span class="wordmark-symbol" aria-hidden="true">BH</span>
        <span>Quản lý bán hàng</span>
    </a>
</header>

<main class="auth-main">
    <section class="auth-card" aria-labelledby="login-title">
        <header class="auth-heading">
            <h1 id="login-title">Đăng nhập</h1>
            <p>Nhập thông tin tài khoản để vào hệ thống.</p>
        </header>

        <c:if test="${not empty notice}">
            <div class="alert alert-success" role="status" aria-live="polite"><c:out value="${notice}"/></div>
        </c:if>
        <c:if test="${not empty formError}">
            <div class="alert alert-error" role="alert" aria-live="assertive"><c:out value="${formError}"/></div>
        </c:if>

        <form id="login-form" class="form-stack" method="post"
              action="${pageContext.request.contextPath}/login" novalidate>
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <div class="form-field">
                <label for="identity">Tên đăng nhập hoặc email</label>
                <input id="identity" name="identity" type="text" maxlength="254"
                       autocomplete="username" autocapitalize="none" spellcheck="false"
                       value="<c:out value='${identity}'/>"
                       aria-describedby="identity-error" aria-invalid="${not empty identityError}">
                <p id="identity-error" class="field-error" aria-live="polite"><c:out value="${identityError}"/></p>
            </div>
            <div class="form-field">
                <div class="field-label-row">
                    <label for="password">Mật khẩu</label>
                    <a href="${pageContext.request.contextPath}/forgot-password">Quên mật khẩu?</a>
                </div>
                <div class="password-control">
                    <input id="password" name="password" type="password" maxlength="128"
                           autocomplete="current-password" aria-describedby="password-error"
                           aria-invalid="${not empty passwordError}">
                    <button class="password-toggle" type="button" data-password-toggle="password"
                            aria-controls="password" aria-pressed="false">Hiện</button>
                </div>
                <p id="password-error" class="field-error" aria-live="polite"><c:out value="${passwordError}"/></p>
            </div>
            <button id="submit-button" class="button button-primary button-block" type="submit">
                <span class="button-label">Đăng nhập</span>
                <span class="button-loading" aria-hidden="true"><span class="spinner"></span>Đang xử lý</span>
            </button>
        </form>
    </section>
</main>
<footer class="auth-bottom">Hệ thống quản lý bán hàng và kho</footer>
</jsp:body>
</layout:page>
