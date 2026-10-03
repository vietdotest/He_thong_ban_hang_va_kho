package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportFlowTest {
    @Test void newImportClearsPreviewReportAndFilenameFromSameSession() {
        var r=mock(HttpServletRequest.class);var session=mock(HttpSession.class);when(r.getSession(false)).thenReturn(session);
        ImportFlow.clear(r,"userImport");
        verify(session).removeAttribute("userImport");verify(session).removeAttribute("userImportReport");verify(session).removeAttribute("userImportFile");
    }
    @Test void sanitizedFilenameKeepsOnlyDisplayName() {
        assertThat(ImportFlow.filename("C:\\fakepath\\nguoi-dung.xlsx\r\n")).isEqualTo("nguoi-dung.xlsx");
        assertThat(ImportFlow.filename("../../products.xlsx")).isEqualTo("products.xlsx");
    }
    @Test void downloadUsesSessionReportAndFailsWhenNewSessionHasNoReport() throws Exception {
        var r=mock(HttpServletRequest.class);var s=mock(HttpServletResponse.class);var session=mock(HttpSession.class);
        when(r.getSession(false)).thenReturn(session);
        var access=new Access(Set.of("ADMIN"),Set.of("USER_MANAGE"),List.of(),List.of(),List.of());
        assertThatThrownBy(()->ImportFlow.download(r,s,"userImport",12,access)).isInstanceOf(IllegalArgumentException.class);
        verify(s,never()).getOutputStream();
        var report=new ImportReport(12,true,"users.xlsx",UserImportService.HEADERS,List.of(new ImportPreview.Line(2,List.of("nv"),"Tạo mới","")));
        when(session.getAttribute("userImportReport")).thenReturn(report);
        var bytes=new ByteArrayOutputStream();
        when(s.getOutputStream()).thenReturn(new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener l){}public void write(int b){bytes.write(b);}});
        ImportFlow.download(r,s,"userImport",12,access);
        assertThat(Xlsx.read(bytes.toByteArray()).get(1).cells()).contains("nv","Đã nhập");
        verify(s).setHeader("Cache-Control","no-store");
        assertThatThrownBy(()->ImportFlow.download(r,s,"userImport",13,access)).isInstanceOf(SecurityException.class);
    }
    @Test void previewAvailabilityRequiresOwnerUnusedTokenAndUnexpiredTime() {
        Instant now=Instant.now();var preview=new ImportPreview(12,List.of(),now);
        assertThat(preview.available(12,now)).isTrue();assertThat(preview.available(13,now)).isFalse();assertThat(preview.available(12,now.plusSeconds(1800))).isFalse();
        preview.claim(12,preview.getToken(),now);assertThat(preview.available(12,now)).isFalse();
        assertThatThrownBy(()->preview.claim(12,preview.getToken(),now)).isInstanceOf(IllegalArgumentException.class);
    }
}
