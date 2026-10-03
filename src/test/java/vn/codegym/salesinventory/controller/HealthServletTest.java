package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthServletTest {
    @Test void reportsOnlyValidatedReleaseShaAndDatabaseHealth() throws Exception {
        DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
        PreparedStatement statement=mock(PreparedStatement.class);ResultSet result=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);when(connection.prepareStatement("SELECT 1")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);
        ServletConfig config=mock(ServletConfig.class);ServletContext context=mock(ServletContext.class);
        when(config.getServletContext()).thenReturn(context);when(context.getAttribute(ApplicationContextKeys.DATA_SOURCE)).thenReturn(source);
        var servlet=new HealthServlet();servlet.init(config);
        String previous=System.getProperty("app.release.sha");
        try {
            for(String sha:new String[]{"a".repeat(40),"unsafe\"text",""}) {
                System.setProperty("app.release.sha",sha);
                var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var output=new StringWriter();
                when(response.getWriter()).thenReturn(new PrintWriter(output));servlet.doGet(request,response);
                verify(response).setStatus(200);
                assertThat(output.toString()).contains("\"status\":\"UP\"","\"database\":\"UP\"");
                if(sha.length()==40)assertThat(output.toString()).contains("\"release\":\""+sha+"\"");
                else assertThat(output.toString()).doesNotContain("release","unsafe");
            }
        }finally{if(previous==null)System.clearProperty("app.release.sha");else System.setProperty("app.release.sha",previous);}
    }
}
