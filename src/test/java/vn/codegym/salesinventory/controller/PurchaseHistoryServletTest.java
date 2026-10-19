package vn.codegym.salesinventory.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.*;

import org.junit.jupiter.api.Test;

import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.PurchaseHistoryService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

class PurchaseHistoryServletTest {
    HttpServletRequest request(String dealer, Set<String> permissions) {
        var request = mock(HttpServletRequest.class);
        var session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER))
                .thenReturn(new CurrentUser(7, "sales", "s@test", "NVKD"));
        when(request.getAttribute("access"))
                .thenReturn(
                        new Access(Set.of("SALES"), permissions, List.of(), List.of(), List.of()));
        when(request.getParameter("dealer")).thenReturn(dealer);
        return request;
    }

    @Test
    void returnsScopedJsonWithoutCachingAndDelegatesTheAuthenticatedActor() throws Exception {
        var service = mock(PurchaseHistoryService.class);
        when(service.load(7, 12))
                .thenReturn(
                        new PurchaseHistoryService.History(
                                LocalDate.of(2026, 7, 19),
                                LocalDate.of(2026, 10, 19),
                                List.of(),
                                null,
                                null,
                                List.of()));
        var response = mock(HttpServletResponse.class);
        var output = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        new PurchaseHistoryServlet(service).get(request("12", Set.of("ORDER_WRITE")), response);
        verify(service).load(7, 12);
        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).setContentType("application/json;charset=UTF-8");
        assertThat(output.toString())
                .contains("2026-07-19", "previousItems")
                .doesNotContain("cost_price");
    }

    @Test
    void rejectsInvalidDealerAndMissingWritePermissionBeforeCallingService() {
        var service = mock(PurchaseHistoryService.class);
        var servlet = new PurchaseHistoryServlet(service);
        assertThatThrownBy(
                        () ->
                                servlet.get(
                                        request("wrong", Set.of("ORDER_WRITE")),
                                        mock(HttpServletResponse.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                servlet.get(
                                        request("12", Set.of("ORDER_READ")),
                                        mock(HttpServletResponse.class)))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(service);
        assertThat(RoutePermissions.required("/api/orders/purchase-history", "GET"))
                .isEqualTo("ORDER_WRITE");
        assertThat(RoutePermissions.required("/api/orders/purchase-history", "POST")).isNull();
    }
}
