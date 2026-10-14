<%@ tag body-content="scriptless" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ taglib prefix="ui" tagdir="/WEB-INF/tags/ui" %>
<%@ attribute name="variant" required="false" type="java.lang.String" %>
<%@ attribute name="icon" required="false" type="java.lang.String" %>
<%@ attribute name="type" required="false" type="java.lang.String" %>
<%@ attribute name="name" required="false" type="java.lang.String" %>
<%@ attribute name="value" required="false" type="java.lang.String" %>
<%@ attribute name="disabled" required="false" type="java.lang.Boolean" %>
<button type="<c:out value='${empty type ? "submit" : type}'/>" class="button button-<c:out value='${empty variant ? "secondary" : variant}'/>" <c:if test="${not empty name}">name="<c:out value='${name}'/>" value="<c:out value='${value}'/>"</c:if> <c:if test="${disabled}">disabled</c:if>><c:if test="${not empty icon}"><ui:icon name="${icon}"/></c:if><jsp:doBody/></button>
