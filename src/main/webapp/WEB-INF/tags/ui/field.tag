<%@ tag body-content="scriptless" pageEncoding="UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ attribute name="label" required="true" type="java.lang.String" %><%@ attribute name="error" required="false" type="java.lang.String" %>
<label class="field"><span><c:out value="${label}"/></span><jsp:doBody/><c:if test="${not empty error}"><span class="field-error"><c:out value="${error}"/></span></c:if></label>
