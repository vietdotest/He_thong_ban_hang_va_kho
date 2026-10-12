package vn.codegym.salesinventory.filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class SecurityHeadersFilterTest {
    @Test void secureRequestGetsHsts() throws Exception {
        var r=mock(HttpServletRequest.class);var s=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        when(r.isSecure()).thenReturn(true);new SecurityHeadersFilter().doFilter(r,s,chain);
        verify(s).setHeader("Strict-Transport-Security","max-age=31536000");verify(chain).doFilter(r,s);
    }
    @Test void plainHttpDoesNotTrustClientForwardedHeader() throws Exception {
        var r=mock(HttpServletRequest.class);var s=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        new SecurityHeadersFilter().doFilter(r,s,chain);
        verify(s,never()).setHeader(eq("Strict-Transport-Security"),anyString());verify(r,never()).getHeader(anyString());verify(chain).doFilter(r,s);
    }
}
