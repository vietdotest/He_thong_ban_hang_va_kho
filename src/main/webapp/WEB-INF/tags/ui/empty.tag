<%@ tag body-content="scriptless" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ taglib prefix="ui" tagdir="/WEB-INF/tags/ui" %>
<%@ attribute name="title" required="true" type="java.lang.String" %><%@ attribute name="icon" required="false" type="java.lang.String" %>
<section class="content-panel empty-state"><c:if test="${not empty icon}"><ui:icon name="${icon}"/></c:if><h2><c:out value="${title}"/></h2><jsp:doBody/></section>
