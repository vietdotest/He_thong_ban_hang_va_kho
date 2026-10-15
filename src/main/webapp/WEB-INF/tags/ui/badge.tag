<%@ tag body-content="scriptless" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ attribute name="tone" required="false" type="java.lang.String" %>
<span class="badge badge-<c:out value='${empty tone ? "neutral" : tone}'/>"><jsp:doBody/></span>
