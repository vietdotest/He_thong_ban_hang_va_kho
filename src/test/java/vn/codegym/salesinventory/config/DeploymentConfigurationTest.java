package vn.codegym.salesinventory.config;
import java.nio.file.*;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import static org.assertj.core.api.Assertions.*;
class DeploymentConfigurationTest {
    @Test void publicTomcatBindsLoopbackAndTrustsOnlyLoopbackProxy()throws Exception{
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var xml=factory.newDocumentBuilder().parse(Path.of("scripts/tomcat-s2-web.xml").toFile());
        var connector=(Element)xml.getElementsByTagName("Connector").item(0);
        assertThat(connector.getAttribute("address")).isEqualTo("127.0.0.1");assertThat(connector.getAttribute("port")).isEqualTo("8081");
        assertThat(Integer.parseInt(connector.getAttribute("maxPostSize"))).isGreaterThan(10*1024*1024);
        var valves=xml.getElementsByTagName("Valve");Element remote=null,access=null;
        for(int i=0;i<valves.getLength();i++){var valve=(Element)valves.item(i);if(valve.getAttribute("className").endsWith("RemoteIpValve"))remote=valve;if(valve.getAttribute("className").endsWith("AccessLogValve"))access=valve;}
        assertThat(remote).isNotNull();var trusted=Pattern.compile(remote.getAttribute("internalProxies"));
        for(String ip:new String[]{"127.0.0.1","::1","0:0:0:0:0:0:0:1"})assertThat(trusted.matcher(ip).matches()).isTrue();
        for(String ip:new String[]{"10.0.0.1","172.16.0.1","192.168.1.1","8.8.8.8"})assertThat(trusted.matcher(ip).matches()).isFalse();
        assertThat(remote.getAttribute("protocolHeader")).isEqualTo("X-Forwarded-Proto");assertThat(remote.getAttribute("remoteIpHeader")).isEqualTo("CF-Connecting-IP");
        assertThat(access.getAttribute("pattern")).doesNotContain("%q","%r","%S");
    }
    @Test void mailConfigurationKeepsLegacyContractAndMasksCredentials(){
        var config=AppConfig.load();assertThat(config.passwordReset().mailPort()).isPositive();assertThat(config.mail().toString()).contains("REDACTED");
    }
}
