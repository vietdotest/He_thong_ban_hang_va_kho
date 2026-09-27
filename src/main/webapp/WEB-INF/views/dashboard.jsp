<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Tổng quan | Quản lý bán hàng</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/app.css">
</head>
<body class="app-page">
<aside class="sidebar">
    <a class="sidebar-brand" href="${pageContext.request.contextPath}/dashboard">
        <span class="wordmark-symbol" aria-hidden="true">BH</span>
        <span>Quản lý bán hàng</span>
    </a>
    <nav class="sidebar-nav" aria-label="Điều hướng chính">
        <a class="nav-item active" href="${pageContext.request.contextPath}/dashboard" aria-current="page">
            <span class="nav-icon" aria-hidden="true">⌂</span>Tổng quan
        </a>
        <a class="nav-item" href="${pageContext.request.contextPath}/account/change-password">
            <span class="nav-icon" aria-hidden="true">⚙</span>Tài khoản
        </a>
    </nav>
    <div class="sidebar-user">
        <span class="avatar" aria-hidden="true"><c:out value="${currentUser.username().substring(0,1).toUpperCase()}"/></span>
        <span class="sidebar-user-name">
            <strong><c:out value="${currentUser.fullName()}"/></strong>
            <small><c:out value="${currentUser.email()}"/></small>
        </span>
    </div>
</aside>

<div class="app-content">
    <header class="app-topbar">
        <div>
            <p class="breadcrumb">Tổng quan</p>
            <h1>Tình hình hôm nay</h1>
        </div>
        <form method="post" action="${pageContext.request.contextPath}/logout">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <button class="button button-secondary" type="submit">Đăng xuất</button>
        </form>
    </header>

    <main class="page-body">
        <section class="summary-grid" aria-label="Số liệu tổng quan">
            <article class="summary-card"><p>Doanh thu hôm nay</p><strong>0 ₫</strong><span>Chưa phát sinh giao dịch</span></article>
            <article class="summary-card"><p>Đơn hàng</p><strong>0</strong><span>Trong ngày hôm nay</span></article>
            <article class="summary-card"><p>Sản phẩm sắp hết</p><strong>0</strong><span>Cần bổ sung tồn kho</span></article>
            <article class="summary-card"><p>Công nợ cần thu</p><strong>0 ₫</strong><span>Đang theo dõi</span></article>
        </section>

        <section class="content-panel">
            <div class="panel-heading"><div><h2>Hoạt động gần đây</h2><p>Các giao dịch mới sẽ xuất hiện tại đây.</p></div></div>
            <div class="empty-state">
                <span class="empty-icon" aria-hidden="true">▤</span>
                <h3>Chưa có hoạt động</h3>
                <p>Dữ liệu sẽ được cập nhật khi có giao dịch đầu tiên.</p>
            </div>
        </section>
    </main>
</div>
</body>
</html>
