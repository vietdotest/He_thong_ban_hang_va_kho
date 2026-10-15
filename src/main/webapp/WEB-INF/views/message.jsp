<%@ page contentType="text/html;charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<layout:page title="Thông báo" view="message" bodyClass="app-page" css="" bare="${empty currentUser}">
<jsp:body>
<main class="page-body ${not empty currentUser ? 'app-content' : 'guest-page'}"><section class="content-panel message-panel"><h1>Thông báo</h1><p><c:out value="${message}"/></p><c:choose><c:when test="${not empty currentUser}"><a class="button" href="<c:out value='${returnPath}'/>">Quay lại biểu mẫu</a><a href="${pageContext.request.contextPath}/dashboard">Về tổng quan</a></c:when><c:otherwise><a class="button" href="${pageContext.request.contextPath}/login">Về đăng nhập</a></c:otherwise></c:choose></section></main>
</jsp:body>
</layout:page>
