package vn.codegym.salesinventory.listener;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Hạ tầng giao diện: Chuẩn bị thông tin tài nguyên giao diện khi ứng dụng khởi động.
 */
public final class UiAssetsListener implements ServletContextListener {

    // Khởi tạo các thành phần khi ứng dụng bắt đầu chạy.
    @Override
    public void contextInitialized(ServletContextEvent event) {
        String sha = System.getProperty("app.release.sha", "");
        event.getServletContext()
                .setAttribute("assetVersion", sha.matches("[a-f0-9]{40}") ? sha : "ui-development");
    }
}
