package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.config.ApplicationContextKeys;

public final class UserImportServlet extends DurableImportServlet {
    private final UserImportService injectedService;

    public UserImportServlet() { this.injectedService = null; }
    UserImportServlet(UserImportService service) { this.injectedService = service; }
    @Override protected String importKind(){return "USER";}
    @Override protected ImportPreview readPreview(long actor,byte[] bytes){return service().preview(actor,bytes);}

    private UserImportService service() {
        return injectedService != null ? injectedService : new UserImportService(source(),
                (UserManagementService) getServletContext().getAttribute(ApplicationContextKeys.USER_MANAGEMENT_SERVICE));
    }

    @Override protected void preparePost(HttpServletRequest request) throws Exception {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) return;
        try { request.getPart("file"); }
        catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                String name = cause.getClass().getName();
                if (name.contains(".fileupload.") && (name.endsWith("SizeLimitExceededException") || name.endsWith("FileSizeLimitExceededException")))
                    throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.", e);
            }
            throw e;
        }
    }

    @Override protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if(injectedService==null){super.get(request,response);return;}
        access(request).require("USER_MANAGE");
        if(value(request,"report").equals("1")){ImportFlow.download(request,response,"userImport",actor(request).id(),access(request));return;}
        if(value(request,"new").equals("1"))ImportFlow.clear(request,"userImport");
        if (value(request, "template").equals("1")) {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=nguoi-dung.xlsx");
            response.getOutputStream().write(Xlsx.write(List.of(UserImportService.HEADERS,
                    List.of("nhanvien01", "nhanvien@example.com", "Nguyễn Văn An", "0901234567", "SALES", "", ""))));
            return;
        }
        Object report=request.getSession().getAttribute("userImportReport");
        Object preview=request.getSession().getAttribute("userImport");
        if(preview instanceof ImportPreview pending && pending.available(actor(request).id(),java.time.Instant.now()))
            ImportFlow.preview(request,"userImport",pending,ImportFlow.filename((String)request.getSession().getAttribute("userImportFile")));
        else if(report instanceof ImportReport saved)ImportFlow.show(request,saved,actor(request).id(),access(request));
        else request.getSession().removeAttribute("userImport");
        render(request, response);
    }

    @Override protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if(injectedService==null){super.post(request,response);return;}
        access(request).require("USER_MANAGE");
        boolean confirm = value(request, "action").equals("confirm");
        try {
            if (confirm) {
                var preview = (ImportPreview) request.getSession().getAttribute("userImport");
                if (preview == null) throw new IllegalArgumentException("Hãy tải lên tệp trước.");
                try {
                    var result = service().confirm(actor(request).id(), preview, value(request, "token"),
                            RequestMetadata.authenticationContext(request));
                    ImportFlow.result(request,"userImport",actor(request).id(),true,UserImportService.HEADERS,result,access(request));
                } finally { request.getSession().removeAttribute("userImport"); }
            } else {
                ImportFlow.clear(request,"userImport");
                var file = request.getPart("file");
                if (file == null || file.getSize() == 0) throw new IllegalArgumentException("Vui lòng chọn tệp XLSX.");
                if (file.getSize() > Xlsx.MAX_BYTES) throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.");
                byte[] bytes;
                try (var input = file.getInputStream()) { bytes = input.readNBytes(Xlsx.MAX_BYTES + 1); }
                var preview = service().preview(actor(request).id(), bytes);
                ImportFlow.preview(request,"userImport",preview,ImportFlow.filename(file.getSubmittedFileName()));
            }
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
            request.setAttribute("errors", Map.of(confirm ? "preview" : "file", e.getMessage()));
        }
        render(request, response);
    }

    @Override protected void badRequest(HttpServletRequest request, HttpServletResponse response, String message) throws ServletException, IOException {
        if(injectedService==null){super.badRequest(request,response,message);return;}
        if ("POST".equals(request.getMethod()))ImportFlow.clear(request,"userImport");
        response.setStatus(400);
        request.setAttribute("errors", Map.of("file", message));
        render(request, response);
    }

    private void render(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setAttribute("userImportPage", true);
        request.setAttribute("title", "Nhập người dùng Excel");
        if(request.getAttribute("headers")==null)request.setAttribute("headers", UserImportService.HEADERS);
        if(request.getAttribute("importStep")==null)request.setAttribute("importStep",1);
        view(request, response, "imports/import");
    }
}
