<%@ tag body-content="scriptless" pageEncoding="UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ attribute name="message" required="true" type="java.lang.String" %><%@ attribute name="tone" required="false" type="java.lang.String" %>
<div class="alert alert-<c:out value='${empty tone ? "success" : tone}'/>" role="status" aria-live="polite"><c:out value="${message}"/><jsp:doBody/></div>
