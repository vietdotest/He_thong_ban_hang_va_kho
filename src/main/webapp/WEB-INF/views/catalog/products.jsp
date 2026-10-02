<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Danh mục sản phẩm</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/css/app.css?v=s205-20261002">
</head>
<body class="app-page">
<%@ include file="../fragments/sidebar.jspf" %>
<main class="page-body app-content products-page">
    <h1>Danh mục sản phẩm</h1>
    <c:if test="${not empty successMessage}"><div class="alert alert-success" role="status"><c:out value="${successMessage}"/></div></c:if>
    <form method="get" class="filter-form">
        <label>Tìm SKU/tên <input name="q" value="<c:out value='${param.q}'/>"></label>
        <label>Nhóm <select name="category"><option value="">Tất cả</option><c:forEach items="${categories}" var="cat"><option value="${cat.id}" ${param.category == cat.id.toString() ? 'selected' : ''}><c:out value="${cat.label}"/></option></c:forEach></select></label>
        <label>Trạng thái <select name="status"><option value="">Tất cả</option><option value="ACTIVE" ${param.status == 'ACTIVE' ? 'selected' : ''}>Đang kinh doanh</option><option value="DISCONTINUED" ${param.status == 'DISCONTINUED' ? 'selected' : ''}>Ngừng kinh doanh</option></select></label>
        <button class="button" type="submit">Tìm</button>
    </form>
    <c:if test="${access.allows('PRODUCT_MANAGE')}">
        <a class="button" href="${pageContext.request.contextPath}/catalog/products/import">Nhập Excel</a>
        <section class="content-panel">
            <h2>${editing or not empty edit.id and edit.id != '0' ? 'Sửa sản phẩm' : 'Thêm sản phẩm'}</h2>
            <c:if test="${not empty formError}"><div class="alert alert-error" role="alert"><c:out value="${formError}"/></div></c:if>
            <form method="post" enctype="multipart/form-data" class="account-form product-form" novalidate>
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
                <div class="form-actions"><button class="button button-primary" type="submit">Lưu sản phẩm</button><c:if test="${not empty edit.id}"><a class="button button-secondary" href="${pageContext.request.contextPath}/catalog/products">Hủy</a></c:if></div>
            </form>
        </section>
    </c:if>
    <div class="table-wrap"><table>
        <thead><tr><th>Ảnh</th><th>SKU</th><th>Tên</th><th>Nhóm</th><th>Đơn vị cơ sở</th><th>Quy cách</th><c:if test="${canCost}"><th>Giá vốn</th></c:if><th>Trạng thái</th><th>Thao tác</th></tr></thead>
        <tbody><c:forEach items="${products}" var="p"><tr>
            <td><c:if test="${not empty p.image_key}"><img width="64" height="64" alt="Ảnh sản phẩm" src="${pageContext.request.contextPath}/catalog/products/image?id=${p.id}"></c:if></td>
            <td><c:out value="${p.sku}"/></td><td><c:out value="${p.name}"/></td><td><c:out value="${p.category_name}"/></td><td><c:out value="${p.base_unit}"/></td><td><c:out value="${p.packaging}"/></td>
            <c:if test="${canCost}"><td><c:out value="${p.cost_price}"/></td></c:if>
            <td>${p.status == 'ACTIVE' ? 'Đang kinh doanh' : 'Ngừng kinh doanh'}</td>
            <td><c:if test="${access.allows('PRODUCT_MANAGE')}"><a href="?id=${p.id}">Sửa / chuyển nhóm</a><form method="post" enctype="multipart/form-data"><input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="${p.id}"><button class="button" type="submit">Xóa</button></form></c:if></td>
        </tr></c:forEach></tbody>
    </table></div>
    <p>Trang <c:out value="${pageNo}"/> (100 dòng/trang)</p>
    <c:url var="next" value="/catalog/products"><c:param name="page" value="${pageNo+1}"/><c:param name="q" value="${param.q}"/><c:param name="category" value="${param.category}"/><c:param name="status" value="${param.status}"/></c:url>
    <a href="<c:out value='${next}'/>">Trang tiếp</a>
    <c:if test="${pageNo > 1}"><c:url var="prev" value="/catalog/products"><c:param name="page" value="${pageNo-1}"/><c:param name="q" value="${param.q}"/><c:param name="category" value="${param.category}"/><c:param name="status" value="${param.status}"/></c:url><a href="<c:out value='${prev}'/>">Trang trước</a></c:if>
</main>
</body>
</html>

