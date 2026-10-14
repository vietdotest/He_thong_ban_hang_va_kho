<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<layout:page title="Quản lý tài khoản" view="admin/users/list" bodyClass="app-page" css="">
<jsp:body>
<div class="app-content">


    <main class="page-body users-page">
<div class="page-heading"><div><h1>Người dùng</h1><p>Tìm tài khoản và xem vai trò được phân công.</p></div>
<div class="page-actions"><a class="button button-secondary" href="${pageContext.request.contextPath}/admin/users/import"><ui:icon name="file-arrow-up"/>Nhập Excel</a><a class="button button-primary" href="${pageContext.request.contextPath}/admin/users/new"><ui:icon name="plus"/> Thêm người dùng</a></div></div>

        <c:if test="${not empty successMessage}"><div class="alert alert-success"><c:out value="${successMessage}"/></div></c:if>

        <section class="content-panel users-panel">
            <form class="user-filters" method="get" action="${pageContext.request.contextPath}/admin/users">
                <label class="filter-search"><span>Tìm kiếm</span><input type="search" name="q" maxlength="150" placeholder="Tên, tài khoản hoặc số điện thoại" value="<c:out value='${criteria.keyword()}'/>"></label>
                <label><span>Vai trò</span><select name="role">
                    <option value="">Tất cả vai trò</option>
                    <c:forEach var="role" items="${roles}">
                        <c:choose>
                            <c:when test="${criteria.roleCode() == role.code()}"><option value="${role.code()}" selected><c:out value="${role.name()}"/></option></c:when>
                            <c:otherwise><option value="${role.code()}"><c:out value="${role.name()}"/></option></c:otherwise>
                        </c:choose>
                    </c:forEach>
                </select></label>
                <label><span>Trạng thái</span><select name="status">
                    <option value="">Tất cả trạng thái</option>
                    <option value="ACTIVE" ${criteria.status() == 'ACTIVE' ? 'selected' : ''}>Đang hoạt động</option>
                    <option value="DISABLED" ${criteria.status() == 'DISABLED' ? 'selected' : ''}>Đã vô hiệu hóa</option>
                    <option value="PENDING_ACTIVATION" ${criteria.status() == 'PENDING_ACTIVATION' ? 'selected' : ''}>Chờ kích hoạt</option>
                    <option value="ADMIN_LOCKED" ${criteria.status() == 'ADMIN_LOCKED' ? 'selected' : ''}>Bị khóa</option>
                </select></label>
                <div class="filter-actions"><ui:button variant="primary" type="submit">Áp dụng</ui:button><a class="button button-secondary" href="${pageContext.request.contextPath}/admin/users">Đặt lại</a></div>
            </form>

            <div class="table-summary">Tìm thấy <strong><c:out value="${userPage.totalItems()}"/></strong> tài khoản</div>
            <div class="table-scroll">
                <table class="data-table">
                    <thead><tr><th>Tên đăng nhập</th><th>Vai trò</th><th>Kho / địa bàn</th><th>Trạng thái</th><th></th></tr></thead>
                    <tbody>
                    <c:forEach var="user" items="${userPage.items()}">
                        <tr>
                            <td><c:out value="${user.username()}"/><small><c:out value="${user.fullName()}"/></small></td>
                            <td><c:out value="${user.roleName()}"/></td>
                            <td><c:choose><c:when test="${not empty user.scopeSummary()}"><c:out value="${user.scopeSummary()}"/></c:when><c:otherwise>Chưa gán kho / địa bàn</c:otherwise></c:choose></td>
                            <td>
                                <c:choose>
                                    <c:when test="${user.status().name() == 'ACTIVE'}"><span class="status-badge status-active">Đang hoạt động</span></c:when>
                                    <c:when test="${user.status().name() == 'DISABLED'}"><span class="status-badge status-disabled">Đã vô hiệu hóa</span></c:when>
                                    <c:when test="${user.status().name() == 'PENDING_ACTIVATION'}"><ui:badge tone="warning">Chờ kích hoạt</ui:badge></c:when><c:otherwise><span class="status-badge status-locked">Bị khóa</span></c:otherwise>
                                </c:choose>
                                <c:if test="${user.mustChangePassword()}"><small class="status-note">Chờ đổi mật khẩu</small></c:if>
                            </td>
                            <td class="table-action"><a class="button button-secondary button-small" href="${pageContext.request.contextPath}/admin/users/edit?id=${user.id()}">Xem</a><a class="button button-secondary button-small" href="${pageContext.request.contextPath}/admin/assignments?id=${user.id()}">Phân công</a></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty userPage.items()}"><tr><td class="table-empty" colspan="5">Không có tài khoản phù hợp với bộ lọc.</td></tr></c:if>
                    </tbody>
                </table>
            </div>

            <ui:pagination page="${pagination}" path="${paginationPath}" pageParameter="${paginationPageParameter}" sizeParameter="${paginationSizeParameter}" filterValues="${paginationFilterValues}" extraParameters="${paginationExtraParameters}"/>
        </section>
    </main>
</div>
</jsp:body>
</layout:page>
