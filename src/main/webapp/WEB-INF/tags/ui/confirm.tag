<%@ tag body-content="scriptless" pageEncoding="UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ attribute name="title" required="true" type="java.lang.String" %>
<details class="confirm-disclosure"><summary class="button button-secondary"><c:out value="${title}"/></summary><jsp:doBody/></details>
