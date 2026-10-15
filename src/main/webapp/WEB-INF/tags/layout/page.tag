<%@ tag body-content="scriptless" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="layout" tagdir="/WEB-INF/tags/layout" %>
<%@ attribute name="title" required="true" type="java.lang.String" %>
<%@ attribute name="view" required="true" type="java.lang.String" %>
<%@ attribute name="bodyClass" required="false" type="java.lang.String" %>
<%@ attribute name="bare" required="false" type="java.lang.Boolean" %>
<%@ attribute name="css" required="false" type="java.lang.String" %>
<%@ attribute name="head" required="false" fragment="true" %>
<!doctype html>
<html lang="vi"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title><c:out value="${title}"/> | Quản lý bán hàng</title>
<%@ include file="../../views/fragments/assets.jspf" %>
<jsp:invoke fragment="head"/>
</head><body class="<c:out value='${bodyClass}'/>" data-view="<c:out value='${view}'/>" data-ui-css="<c:out value='${css}'/>">
<c:if test="${not bare}"><layout:sidebar/><layout:topbar/></c:if>
<jsp:doBody/>
</body></html>
