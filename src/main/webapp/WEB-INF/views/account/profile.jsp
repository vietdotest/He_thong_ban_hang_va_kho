<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Hồ sơ cá nhân</title>
    <%@ include file="../fragments/assets.jspf" %>
</head>
<body class="app-page">
<%@ include file="../fragments/sidebar.jspf" %>
<%@ include file="../fragments/topbar.jspf" %>
<main class="page-body app-content profile-page">
    <h1>Hồ sơ cá nhân</h1>
    <c:if test="${param.notice == 'saved'}"><div class="alert" role="status">Đã lưu hồ sơ.</div></c:if>
    <section class="profile-section" aria-labelledby="contact-heading">
        <h2 id="contact-heading">Thông tin liên hệ</h2>
        <form method="post" class="account-form" action="${pageContext.request.contextPath}/account/profile">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <div class="form-grid">
                <label class="field" for="profile-name">Họ tên
                    <input id="profile-name" name="fullName" required minlength="2" maxlength="150" autocomplete="name"
                           value="<c:out value='${form.fullName}'/>" aria-invalid="${not empty errors.fullName}" aria-describedby="profile-name-error">
                    <small id="profile-name-error" class="field-error" aria-live="polite"><c:out value="${errors.fullName}"/></small>
                </label>
                <label class="field" for="profile-phone">Số điện thoại
                    <input id="profile-phone" type="tel" name="phone" required maxlength="20" autocomplete="tel"
                           value="<c:out value='${form.phone}'/>" aria-invalid="${not empty errors.phone}" aria-describedby="profile-phone-error">
                    <small id="profile-phone-error" class="field-error" aria-live="polite"><c:out value="${errors.phone}"/></small>
                </label>
            </div>
            <button type="submit" class="button button-primary">Lưu hồ sơ</button>
        </form>
    </section>
    <section class="profile-section" aria-labelledby="assignment-heading">
        <h2 id="assignment-heading">Tài khoản và phân công</h2>
        <dl class="profile-details">
            <div><dt>Tên đăng nhập</dt><dd><c:out value="${profile.username}"/></dd></div>
            <div><dt>Email</dt><dd><c:out value="${profile.email}"/></dd></div>
            <div><dt>Vai trò</dt><dd><c:choose><c:when test="${empty access.roleNames()}">Chưa phân công</c:when><c:otherwise><c:forEach var="role" items="${access.roleNames()}"><span><c:out value="${role.name}"/></span></c:forEach></c:otherwise></c:choose></dd></div>
            <div><dt>Kho</dt><dd><c:choose><c:when test="${empty access.warehouses()}">Chưa phân công</c:when><c:otherwise><c:forEach var="warehouse" items="${access.warehouses()}"><span><c:out value="${warehouse.name}"/></span></c:forEach></c:otherwise></c:choose></dd></div>
            <div><dt>Địa bàn</dt><dd><c:choose><c:when test="${empty access.territories()}">Chưa phân công</c:when><c:otherwise><c:forEach var="territory" items="${access.territories()}"><span><c:out value="${territory.name}"/></span></c:forEach></c:otherwise></c:choose></dd></div>
        </dl>
    </section>
    <section class="profile-section" aria-labelledby="avatar-heading">
        <h2 id="avatar-heading">Ảnh đại diện</h2>
        <c:if test="${not empty profile.avatar_key}"><img width="128" height="128" alt="Ảnh đại diện" src="${pageContext.request.contextPath}/account/avatar"></c:if>
        <form method="post" enctype="multipart/form-data" action="${pageContext.request.contextPath}/account/avatar">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <label class="field">Ảnh JPG/PNG tối đa 2MB <input type="file" name="image" accept="image/jpeg,image/png" required aria-invalid="${not empty errors.image}" aria-describedby="avatar-error">
                <small id="avatar-error" class="field-error" role="alert"><c:out value="${errors.image}"/></small>
            </label>
            <button type="submit" class="button button-primary">Cập nhật ảnh</button>
        </form>
    </section>
</main>
</body>
</html>
