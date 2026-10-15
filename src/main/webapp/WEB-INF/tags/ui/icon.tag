<%@ tag body-content="empty" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ attribute name="name" required="true" type="java.lang.String" %>
<%@ attribute name="label" required="false" type="java.lang.String" %>
<%@ attribute name="cssClass" required="false" type="java.lang.String" %>
<c:url var="sprite" value="/assets/icons/fa-sprite.svg"><c:param name="v" value="${applicationScope.assetVersion}"/></c:url>
<svg class="icon <c:out value='${cssClass}'/>" focusable="false" <c:choose><c:when test="${empty label}">aria-hidden="true"</c:when><c:otherwise>role="img" aria-label="<c:out value='${label}'/>"</c:otherwise></c:choose>><use href="<c:out value='${sprite}'/>#<c:out value='${name}'/>"/></svg>
