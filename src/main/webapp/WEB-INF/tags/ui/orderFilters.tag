<%@ tag body-content="empty" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:forEach var="key" items="${['q','status','page','pageSize','staff','territory','from','to']}"><input type="hidden" name="${key}" value="<c:out value='${param[key]}'/>"></c:forEach>
<input type="hidden" name="filterDealer" value="<c:out value='${param.dealer}'/>">
