package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.ApplicationContextKeys;

public final class HealthServlet extends HttpServlet {
    private DataSource dataSource;

    @Override
    public void init() throws ServletException {
        Object configuredDataSource = getServletContext().getAttribute(ApplicationContextKeys.DATA_SOURCE);
        if (!(configuredDataSource instanceof DataSource source)) {
            throw new ServletException("Application data source is not initialized");
        }
        this.dataSource = source;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                response.setStatus(HttpServletResponse.SC_OK);
                String sha=System.getProperty("app.release.sha","");
                String release=sha.matches("[a-f0-9]{40}")?",\"release\":\""+sha+"\"":"";
                response.getWriter().write("{\"status\":\"UP\",\"database\":\"UP\""+release+"}");
                return;
            }
        } catch (SQLException exception) {
            getServletContext().log("Health check failed", exception);
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write("{\"status\":\"DOWN\",\"database\":\"DOWN\"}");
    }
}
