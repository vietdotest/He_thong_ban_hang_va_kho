package vn.codegym.salesinventory.controller;

import java.nio.file.*;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ImportTemplateSecurityTest {
    @Test void jspLoopsCannotShadowImplicitRequestObjects() throws Exception {
        var implicit=Set.of("header","headerValues","cookie","param","paramValues","pageContext","requestScope","sessionScope","applicationScope","initParam");
        var variables=Pattern.compile("<c:(?:forEach|set)\\b[^>]*\\bvar=\"([^\"]+)\"");
        try(var files=Files.walk(Path.of("src/main/webapp/WEB-INF"))) {
            for(var file:files.filter(p->p.toString().endsWith(".jsp")||p.toString().endsWith(".jspf")||p.toString().endsWith(".tag")).toList()) {
                var matcher=variables.matcher(Files.readString(file));
                while(matcher.find())assertThat(implicit).as("Biến JSP trong %s",file).doesNotContain(matcher.group(1));
            }
        }
    }
}
