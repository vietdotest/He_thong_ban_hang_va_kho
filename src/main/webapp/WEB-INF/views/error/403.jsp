<%@ page contentType="text/html;charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="currentUser" value="${sessionScope['auth.currentUser']}"/>
<layout:page title="403 — Không đủ quyền truy cập" view="error/403" bodyClass="app-page" css="" bare="${empty currentUser}">
<jsp:body>
<main class="page-body ${not empty currentUser ? 'app-content' : 'guest-page'}"><section class="content-panel message-panel"><p class="breadcrumb">Thông báo 403</p><h1>Không đủ quyền truy cập</h1><p>Bạn chưa được cấp quyền cho thao tác này. Hãy quay lại phần việc của mình hoặc liên hệ quản trị hệ thống.</p><c:choose><c:when test="${not empty currentUser}"><a class="button button-primary" href="${pageContext.request.contextPath}/dashboard">Về phần việc của tôi</a></c:when><c:otherwise><a class="button button-primary" href="${pageContext.request.contextPath}/login">Đăng nhập</a></c:otherwise></c:choose></section></main>
</jsp:body>
</layout:page>
