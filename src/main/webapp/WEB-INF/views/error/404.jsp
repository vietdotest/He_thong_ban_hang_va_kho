<%@ page contentType="text/html;charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="currentUser" value="${sessionScope['auth.currentUser']}"/>
<layout:page title="404 — Trang không tồn tại" view="error/404" bodyClass="app-page" css="" bare="${empty currentUser}">
<jsp:body>
<main class="page-body ${not empty currentUser ? 'app-content' : 'guest-page'}"><section class="content-panel message-panel"><p class="breadcrumb">Thông báo 404</p><h1>Trang không tồn tại</h1><p>Đường dẫn không tồn tại hoặc dữ liệu đã được chuyển sang vị trí khác.</p><c:choose><c:when test="${not empty currentUser}"><a class="button button-primary" href="${pageContext.request.contextPath}/dashboard">Về phần việc của tôi</a></c:when><c:otherwise><a class="button button-primary" href="${pageContext.request.contextPath}/login">Đăng nhập</a></c:otherwise></c:choose></section></main>
</jsp:body>
</layout:page>
