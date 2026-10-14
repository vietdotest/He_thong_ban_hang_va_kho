package vn.codegym.salesinventory.controller;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UiBuildContractTest {
    @Test void builtStylesAndSpriteMeetTransferBudget() throws Exception {
        byte[] css=Files.readAllBytes(Path.of("target/generated-webapp/assets/css/ui.css"));
        var compressed=new ByteArrayOutputStream();
        try(var gzip=new GZIPOutputStream(compressed)){gzip.write(css);}
        assertThat(css).isNotEmpty();assertThat(compressed.size()).as("Ngân sách ui.css gzip").isLessThan(60*1024);
        assertThat(Files.size(Path.of("src/main/webapp/assets/icons/fa-sprite.svg"))).isLessThan(40L*1024);
        String built=new String(css,java.nio.charset.StandardCharsets.UTF_8);
        for(String name:Files.readAllLines(Path.of("src/test/resources/ui/styled-classes.txt"))) {
            assertThat(built).as("Class trình bày được JS/test sử dụng: %s",name).contains("."+name);
        }
    }
    @Test void allStaticIconNamesExistAndLegacyImagesAreGone() throws Exception {
        String sprite=Files.readString(Path.of("src/main/webapp/assets/icons/fa-sprite.svg"));
        Set<String> names=new HashSet<>();var ids=Pattern.compile("<symbol id=\"([^\"]+)\"").matcher(sprite);
        while(ids.find())assertThat(names.add(ids.group(1))).isTrue();
        try(var files=Files.walk(Path.of("src/main/webapp/WEB-INF"))){
            for(var file:files.filter(f->f.toString().matches(".*\\.(jsp|jspf|tag)$")).toList()){
                String content=Files.readString(file);
                assertThat(Pattern.compile("<img[^>]+assets/icons/",Pattern.DOTALL).matcher(content).find()).as("Icon cũ trong %s",file).isFalse();
                var icons=Pattern.compile("<ui:icon[^>]*name=\"([a-z0-9-]+)\"").matcher(content);
                while(icons.find())assertThat(names).as("Tên icon trong %s",file).contains(icons.group(1));
            }
        }
    }
}
