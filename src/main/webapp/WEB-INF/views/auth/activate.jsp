<%@ page contentType="text/html;charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<layout:page title="Kích hoạt tài khoản" view="auth/activate" bodyClass="app-page" css="" bare="true">
<jsp:body>
<main class="page-body guest-page"><section class="content-panel message-panel"><h1>Kích hoạt tài khoản</h1><p>Xác nhận kích hoạt để đăng nhập bằng mật khẩu tạm trong email. Bạn cần đổi mật khẩu ngay ở lần đăng nhập đầu tiên.</p><form method="post"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="token" value="<c:out value='${token}'/>"><ui:button variant="primary">Kích hoạt tài khoản</ui:button></form><a href="${pageContext.request.contextPath}/login">Về đăng nhập</a></section></main>
</jsp:body>
</layout:page>
