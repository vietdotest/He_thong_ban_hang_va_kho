<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Hồ sơ cá nhân | Quản lý bán hàng</title>
    <%@ include file="../fragments/assets.jspf" %>
</head>
<body class="app-page">
<%@ include file="../fragments/sidebar.jspf" %>
<%@ include file="../fragments/topbar.jspf" %>
<main class="page-body app-content profile-page">
    <div class="page-heading"><div><h1>Hồ sơ cá nhân</h1><p>Cập nhật thông tin để đồng nghiệp liên hệ khi cần.</p></div></div>
    <c:if test="${param.notice == 'saved'}"><div class="alert alert-success" role="status">Đã lưu thay đổi hồ sơ.</div></c:if>
    <div class="profile-layout">
        <section class="content-panel" aria-labelledby="contact-heading">
            <h2 id="contact-heading">Thông tin cá nhân</h2>
            <form method="post" enctype="multipart/form-data" class="account-form" action="${pageContext.request.contextPath}/account/profile">
                <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                <label class="field" for="profile-name">Họ và tên
                    <input id="profile-name" name="fullName" required minlength="2" maxlength="150" autocomplete="name"
                           value="<c:out value='${form.fullName}'/>" aria-invalid="${not empty errors.fullName}" aria-describedby="profile-name-error">
                    <small id="profile-name-error" class="field-error" aria-live="polite"><c:out value="${errors.fullName}"/></small>
                </label>
                <label class="field" for="profile-phone">Số điện thoại
                    <input id="profile-phone" type="tel" name="phone" required maxlength="20" autocomplete="tel"
                           value="<c:out value='${form.phone}'/>" aria-invalid="${not empty errors.phone}" aria-describedby="profile-phone-error">
                    <small id="profile-phone-error" class="field-error" aria-live="polite"><c:out value="${errors.phone}"/></small>
                </label>
                <label class="field" for="profile-image">Ảnh đại diện</label>
                <div class="avatar-picker">
                    <c:choose><c:when test="${not empty profile.avatar_key}"><img alt="Ảnh đại diện hiện tại" src="${pageContext.request.contextPath}/account/avatar"></c:when>
                        <c:otherwise><span class="avatar-placeholder" aria-label="Chưa có ảnh đại diện">BH</span></c:otherwise></c:choose>
                    <label class="button button-secondary file-button" for="profile-image"><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/upload.svg">Chọn ảnh JPG / PNG
                        <input id="profile-image" class="file-input" type="file" name="image" accept="image/jpeg,image/png" aria-invalid="${not empty errors.image}" aria-describedby="avatar-helper avatar-error">
                    </label>
                </div>
                <p id="avatar-helper" class="muted">Tối đa 2 MB. Ảnh sẽ được cắt vuông. Để trống để giữ ảnh hiện tại.</p>
                <p id="avatar-error" class="field-error" role="alert"><c:out value="${errors.image}"/></p>
                <div class="form-actions"><button type="submit" class="button button-primary">Lưu thay đổi</button></div>
            </form>
        </section>
        <section class="content-panel" aria-labelledby="assignment-heading">
            <h2 id="assignment-heading">Thông tin được phân công</h2>
            <dl class="profile-details">
                <div><dt>Tên đăng nhập</dt><dd><c:out value="${profile.username}"/></dd></div>
                <div><dt>Email</dt><dd><c:out value="${profile.email}"/></dd></div>
                <div><dt>Vai trò</dt><dd><c:choose><c:when test="${empty access.roleNames()}">Chưa phân công</c:when><c:otherwise><c:forEach var="role" items="${access.roleNames()}"><span><c:out value="${role.name}"/></span></c:forEach></c:otherwise></c:choose></dd></div>
                <div><dt>Kho / địa bàn</dt><dd><c:choose><c:when test="${empty access.warehouses() and empty access.territories()}">Chưa phân công</c:when><c:otherwise><c:forEach var="warehouse" items="${access.warehouses()}"><span><c:out value="${warehouse.name}"/></span></c:forEach><c:forEach var="territory" items="${access.territories()}"><span><c:out value="${territory.name}"/></span></c:forEach></c:otherwise></c:choose></dd></div>
            </dl>
            <div class="alert"><strong>Được quản lý bởi quản trị viên</strong>Liên hệ người quản trị khi cần đổi vai trò, kho hoặc địa bàn.</div>
        </section>
    </div>
</main>
</body>
</html>
