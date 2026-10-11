<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<fmt:setLocale value="vi_VN"/>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Danh mục sản phẩm</title>
    <%@ include file="../fragments/assets.jspf" %>
</head>
<body class="app-page">
<%@ include file="../fragments/sidebar.jspf" %>
<%@ include file="../fragments/topbar.jspf" %>
<main class="page-body app-content products-page">
    <div class="page-heading"><div><h1>${access.allows('PRODUCT_MANAGE') ? 'Sản phẩm' : 'Tra cứu sản phẩm'}</h1><p>Quản lý mã hàng, quy cách đóng gói và trạng thái kinh doanh.</p></div>
        <c:if test="${access.allows('PRODUCT_MANAGE')}"><div class="page-actions"><a class="button button-secondary" href="${pageContext.request.contextPath}/catalog/products/import"><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/upload-outline.svg">Nhập Excel</a><a class="button button-primary" href="<c:out value='${pageContext.request.contextPath}${productReturn}'/>${productReturn.contains('?') ? '&amp;' : '?'}new=1#editor" data-editor="editor">＋ Thêm sản phẩm</a></div></c:if>
    </div>
    <c:if test="${not empty successMessage}"><div class="alert alert-success" role="status"><c:out value="${successMessage}"/></div></c:if>
    <c:if test="${access.allows('PRODUCT_MANAGE')}"><details class="content-panel disclosure editor" id="editor" ${editing or not empty edit.id or not empty errors or param['new'] == '1' ? 'open' : ''}>
        <summary>${editing or not empty edit.id ? 'Sửa sản phẩm' : 'Thêm sản phẩm'}</summary>
        <c:if test="${not empty formError}"><div class="alert alert-error" role="alert"><c:out value="${formError}"/></div></c:if>
            <form method="post" enctype="multipart/form-data" class="account-form product-form form-grid" novalidate>
                <input type="hidden" name="q" value="<c:out value='${productFilter.q}'/>"><input type="hidden" name="filterCategory" value="<c:out value='${productFilter.category}'/>"><input type="hidden" name="filterStatus" value="<c:out value='${productFilter.status}'/>"><input type="hidden" name="page" value="${pagination.page}"><input type="hidden" name="pageSize" value="${pagination.pageSize}">
                <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
                <input type="hidden" name="id" value="<c:out value='${edit.id}'/>">
                <input type="hidden" name="version" value="<c:out value='${edit.version}'/>">
                <label class="field">SKU <input name="sku" required maxlength="64" value="<c:out value='${edit.sku}'/>" aria-invalid="${not empty errors.sku}" aria-describedby="sku-error"><c:if test="${not empty errors.sku}"><small class="field-error" id="sku-error"><c:out value="${errors.sku}"/></small></c:if></label>
                <label class="field">Tên sản phẩm <input name="name" required maxlength="200" value="<c:out value='${edit.name}'/>" aria-invalid="${not empty errors.name}" aria-describedby="name-error"><c:if test="${not empty errors.name}"><small class="field-error" id="name-error"><c:out value="${errors.name}"/></small></c:if></label>
                <label class="field">Nhóm hàng <select name="category" required aria-invalid="${not empty errors.category}" aria-describedby="category-error"><option value="">Chọn nhóm hàng</option><c:if test="${not empty errors.category and not empty edit.category_id}"><option selected value="<c:out value='${edit.category_id}'/>"><c:out value="${edit.category_id}"/></option></c:if><c:forEach items="${categories}" var="cat"><option value="${cat.id}" ${not empty edit.category_id and edit.category_id.toString() == cat.id.toString() ? 'selected' : ''}><c:out value="${cat.label}"/></option></c:forEach></select><c:if test="${not empty errors.category}"><small class="field-error" id="category-error"><c:out value="${errors.category}"/></small></c:if></label>
                <label class="field">Đơn vị cơ sở <input name="baseUnit" required maxlength="50" value="<c:out value='${edit.base_unit}'/>" aria-invalid="${not empty errors.baseUnit}" aria-describedby="base-unit-error"><c:if test="${not empty errors.baseUnit}"><small class="field-error" id="base-unit-error"><c:out value="${errors.baseUnit}"/></small></c:if></label>
                <label class="field">Quy cách <input name="packaging" maxlength="250" value="<c:out value='${edit.packaging}'/>" aria-invalid="${not empty errors.packaging}" aria-describedby="packaging-error"><c:if test="${not empty errors.packaging}"><small class="field-error" id="packaging-error"><c:out value="${errors.packaging}"/></small></c:if></label>
                <c:if test="${access.allows('COST_WRITE')}"><label class="field">Giá vốn <input name="cost" type="text" inputmode="decimal" maxlength="64" value="<c:out value='${edit.cost_price}'/>" aria-invalid="${not empty errors.cost}" aria-describedby="cost-error"><c:if test="${not empty errors.cost}"><small class="field-error" id="cost-error"><c:out value="${errors.cost}"/></small></c:if></label></c:if>
                <label class="field">Ảnh JPG/PNG tối đa 2MB <input type="file" name="image" accept="image/jpeg,image/png" aria-invalid="${not empty errors.image}" aria-describedby="image-error"><c:if test="${not empty errors.image}"><small class="field-error" id="image-error"><c:out value="${errors.image}"/></small></c:if></label>
                <label class="field">Trạng thái <select name="status" aria-invalid="${not empty errors.status}" aria-describedby="status-error"><c:if test="${not empty errors.status}"><option selected value="<c:out value='${edit.status}'/>"><c:out value="${edit.status}"/></option></c:if><option value="ACTIVE">Đang kinh doanh</option><option value="DISCONTINUED" ${edit.status == 'DISCONTINUED' ? 'selected' : ''}>Ngừng kinh doanh</option></select><c:if test="${not empty errors.status}"><small class="field-error" id="status-error"><c:out value="${errors.status}"/></small></c:if></label>
                <div class="form-actions form-full"><button class="button button-primary" type="submit">Lưu sản phẩm</button><a class="button button-secondary" href="<c:out value='${pageContext.request.contextPath}${productReturn}'/>">Hủy</a></div>
            </form>
<c:if test="${not empty edit.id and edit.id > 0}"><div class="account-form"><form method="post" data-confirm="Bạn có chắc muốn xóa sản phẩm này?"><input type="hidden" name="q" value="<c:out value='${productFilter.q}'/>"><input type="hidden" name="filterCategory" value="<c:out value='${productFilter.category}'/>"><input type="hidden" name="filterStatus" value="<c:out value='${productFilter.status}'/>"><input type="hidden" name="page" value="${pagination.page}"><input type="hidden" name="pageSize" value="${pagination.pageSize}"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="${edit.id}"><button class="button button-secondary">Xóa</button></form></div></c:if>
    </details></c:if>
    <form method="get" class="filter-form product-filters">
        <input type="hidden" name="pageSize" value="${pagination.pageSize}"><input type="hidden" name="category" value="<c:out value='${productFilter.category}'/>" class="desktop-category-value">
        <label class="filter-search"><span class="sr-only">Tìm sản phẩm</span><input type="search" name="q" data-lookup="products" maxlength="150" placeholder="Tìm SKU hoặc tên sản phẩm" value="<c:out value='${productFilter.q}'/>"></label>
        <label class="mobile-category-filter"><span class="sr-only">Nhóm hàng</span><select name="category" disabled><option value="">Tất cả nhóm hàng</option><c:forEach var="cat" items="${categories}"><option value="${cat.id}" ${productFilter.category == cat.id.toString() ? 'selected' : ''}><c:out value="${cat.label}"/></option></c:forEach></select></label>
        <label><span class="sr-only">Trạng thái</span><select name="status"><option value="">Tất cả trạng thái</option><option value="ACTIVE" ${productFilter.status == 'ACTIVE' ? 'selected' : ''}>Đang bán</option><option value="DISCONTINUED" ${productFilter.status == 'DISCONTINUED' ? 'selected' : ''}>Ngừng bán</option></select></label>
        <div class="filter-actions"><button class="button button-secondary"><img class="icon" alt="" src="${pageContext.request.contextPath}/assets/icons/filter.svg">Áp dụng</button><c:if test="${not empty productFilter.q or not empty productFilter.category or not empty productFilter.status}"><a class="button button-secondary" href="${pageContext.request.contextPath}/catalog/products">Bỏ bộ lọc</a></c:if></div>
    </form>
    <div class="product-layout">
        <aside class="content-panel category-tree" aria-label="Lọc theo nhóm hàng"><h2>Nhóm hàng</h2>
            <c:url var="allProducts" value="/catalog/products"><c:param name="q" value="${productFilter.q}"/><c:param name="status" value="${productFilter.status}"/><c:param name="pageSize" value="${pagination.pageSize}"/></c:url>
            <a class="category-link ${empty productFilter.category ? 'active' : ''}" href="<c:out value='${allProducts}'/>">Tất cả sản phẩm</a>
            <c:forEach items="${categories}" var="cat"><c:url var="categoryLink" value="/catalog/products"><c:param name="q" value="${productFilter.q}"/><c:param name="status" value="${productFilter.status}"/><c:param name="category" value="${cat.id}"/><c:param name="pageSize" value="${pagination.pageSize}"/></c:url>
                <a class="category-link ${productFilter.category == cat.id.toString() ? 'active' : ''} depth-${cat.depth > 3 ? 3 : cat.depth}" href="<c:out value='${categoryLink}'/>" aria-current="${productFilter.category == cat.id.toString() ? 'true' : 'false'}"><c:out value="${cat.name}"/></a>
            </c:forEach>
        </aside>
        <div class="product-results">
            <div class="table-wrap product-table"><table class="data-table">
                <thead><tr><th>SKU</th><th>Tên sản phẩm</th><th>ĐVT</th><th>Quy cách</th><c:if test="${canCost}"><th>Giá vốn</th></c:if><th>Trạng thái</th><c:if test="${access.allows('PRODUCT_MANAGE')}"><th>Thao tác</th></c:if></tr></thead>
                <tbody><c:forEach items="${products}" var="p"><tr>
                    <td><c:out value="${p.sku}"/></td><td><div class="product-name"><c:if test="${not empty p.image_key}"><img width="36" height="36" alt="" src="${pageContext.request.contextPath}/catalog/products/image?id=${p.id}"></c:if><c:out value="${p.name}"/></div></td>
                    <td><c:out value="${p.base_unit}"/></td><td><c:out value="${p.packaging}"/></td>
                    <c:if test="${canCost}"><td class="money"><c:choose><c:when test="${empty p.cost_price}">Chưa có</c:when><c:otherwise><fmt:formatNumber value="${p.cost_price}" pattern="#,##0.####"/> đ</c:otherwise></c:choose></td></c:if>
                    <td><span class="badge ${p.status == 'ACTIVE' ? 'badge-success' : 'badge-neutral'}">${p.status == 'ACTIVE' ? 'Đang bán' : 'Ngừng bán'}</span></td>
                    <c:if test="${access.allows('PRODUCT_MANAGE')}"><td class="table-action"><a class="button button-plain" href="<c:out value='${pageContext.request.contextPath}${productReturn}'/>${productReturn.contains('?') ? '&amp;' : '?'}id=${p.id}#editor" aria-label="Sửa sản phẩm ${p.sku}">Sửa</a></td></c:if>
                </tr></c:forEach></tbody>
            </table></div>
            <div class="product-cards"><c:forEach items="${products}" var="p"><article class="product-card">
                <div class="product-card-header"><span><c:out value="${p.sku}"/></span><span class="badge ${p.status == 'ACTIVE' ? 'badge-success' : 'badge-neutral'}">${p.status == 'ACTIVE' ? 'Đang bán' : 'Ngừng bán'}</span></div>
                <h2><c:out value="${p.name}"/></h2><div class="product-card-meta"><span>ĐVT: <c:out value="${p.base_unit}"/></span><span><c:out value="${p.packaging}"/></span></div>
                <c:if test="${canCost}"><p class="muted">Giá vốn: <c:choose><c:when test="${empty p.cost_price}">Chưa có</c:when><c:otherwise><fmt:formatNumber value="${p.cost_price}" pattern="#,##0.####"/> đ / <c:out value="${p.base_unit}"/></c:otherwise></c:choose></p></c:if>
                <c:if test="${access.allows('PRODUCT_MANAGE')}"><a class="button button-secondary" href="<c:out value='${pageContext.request.contextPath}${productReturn}'/>${productReturn.contains('?') ? '&amp;' : '?'}id=${p.id}#editor">Sửa sản phẩm</a></c:if>
            </article></c:forEach></div>
            <c:if test="${empty products}"><div class="content-panel empty-state"><h2>Không có sản phẩm phù hợp</h2><p>Thử thay đổi từ khóa, nhóm hàng hoặc trạng thái.</p><a class="button button-secondary" href="${pageContext.request.contextPath}/catalog/products">Bỏ bộ lọc</a></div></c:if>
        </div>
    </div>
    <%@ include file="../fragments/pagination.jspf" %>
    <c:if test="${canCost}"><div class="alert"><strong>Giá vốn theo đơn vị cơ sở</strong>Giá vốn được tính trên một đơn vị cơ sở của từng sản phẩm. Quy cách đóng gói được hiển thị để đối chiếu.</div></c:if>
</main></body></html>
