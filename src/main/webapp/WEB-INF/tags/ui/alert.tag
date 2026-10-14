<%@ tag body-content="scriptless" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ taglib prefix="ui" tagdir="/WEB-INF/tags/ui" %>
<%@ attribute name="tone" required="false" type="java.lang.String" %><%@ attribute name="title" required="false" type="java.lang.String" %>
<div class="alert alert-<c:out value='${empty tone ? "warning" : tone}'/>" role="${tone == 'error' ? 'alert' : 'status'}"><ui:icon name="${tone == 'error' ? 'triangle-exclamation' : tone == 'success' ? 'circle-check' : 'circle-info'}"/><c:if test="${not empty title}"><strong><c:out value="${title}"/></strong></c:if><jsp:doBody/></div>
