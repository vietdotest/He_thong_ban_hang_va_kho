package vn.codegym.salesinventory.listener;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/** Static asset version shares the SHA checked by the deployment health gate. */
public final class UiAssetsListener implements ServletContextListener {
    @Override public void contextInitialized(ServletContextEvent event) {
        String sha=System.getProperty("app.release.sha", "");
        event.getServletContext().setAttribute("assetVersion",sha.matches("[a-f0-9]{40}")?sha:"ui-development");
    }
}
