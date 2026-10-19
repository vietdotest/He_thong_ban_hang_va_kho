package vn.codegym.salesinventory.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/**
 * Dùng chung: Đặt mã hóa UTF-8 cho yêu cầu và phản hồi để hiển thị đúng tiếng Việt.
 */
public final class CharacterEncodingFilter implements Filter {

    // Đặt UTF-8 cho yêu cầu và phản hồi trước khi xử lý tiếp.
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        chain.doFilter(request, response);
    }
}
