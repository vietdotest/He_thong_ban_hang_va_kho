package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.service.ImportPreview;
import vn.codegym.salesinventory.service.ImportReport;

final class ImportFlow {
    private ImportFlow() {}
    static String filename(String supplied) {
        if(supplied==null||supplied.isBlank())return "du-lieu.xlsx";
        String name=supplied.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        name=name.replaceAll("[\\p{Cntrl}]","");return name.substring(0,Math.min(180,name.length()));
    }
    static void clear(HttpServletRequest request,String key) {
        var session=request.getSession(false);
        if(session!=null){session.removeAttribute(key);session.removeAttribute(key+"Report");session.removeAttribute(key+"File");}
    }
    static void preview(HttpServletRequest r,String key,ImportPreview preview,String filename) {
        r.getSession().setAttribute(key,preview);r.getSession().setAttribute(key+"File",filename);
        r.setAttribute("preview",preview);r.setAttribute("filename",filename);r.setAttribute("rowCount",preview.getLines().size());
        summary(r,preview.getLines(),false);r.setAttribute("importStep",2);
    }
    static void result(HttpServletRequest r,String key,long actor,boolean users,List<String> headers,List<ImportPreview.Line> lines,Access access) {
        Object name=r.getSession().getAttribute(key+"File");
        var report=new ImportReport(actor,users,filename(name==null?null:name.toString()),headers,lines);
        r.getSession().setAttribute(key+"Report",report);show(r,report,actor,access);
    }
    static void show(HttpServletRequest r,ImportReport report,long actor,Access access) {
        var rows=report.lines(actor,access);
        r.setAttribute("report",rows);r.setAttribute("headers",report.headers(actor,access));r.setAttribute("filename",report.getFilename());
        r.setAttribute("rowCount",rows.size());r.setAttribute("importStep",3);summary(r,rows,true);
    }
    static void summary(HttpServletRequest r,List<ImportPreview.Line> rows,boolean done) {
        long valid=rows.stream().filter(ImportPreview.Line::isValid).count(),invalid=rows.size()-valid;
        r.setAttribute(done?"successCount":"validCount",valid);r.setAttribute(done?"failureCount":"invalidCount",invalid);
        r.setAttribute("createdCount",rows.stream().filter(ImportPreview.Line::isValid).filter(l->l.operation().equals("Tạo mới")).count());
        r.setAttribute("updatedCount",rows.stream().filter(ImportPreview.Line::isValid).filter(l->l.operation().equals("Cập nhật")).count());
        r.setAttribute("skippedCount",invalid);
        var names=java.util.Map.of("ADMIN","Quản trị hệ thống","SALES_MANAGER","Quản lý kinh doanh","SALES","Nhân viên kinh doanh","WAREHOUSE_MANAGER","Trưởng kho","WAREHOUSE","Nhân viên kho","ACCOUNTANT","Kế toán","DIRECTOR","Ban giám đốc");
        var roles=new java.util.HashMap<Integer,String>();
        for(var line:rows) if(line.cells().size()>4) roles.put(line.number(),java.util.Arrays.stream(line.cells().get(4).split("[,;]")).map(String::trim).map(code->names.getOrDefault(code,code)).collect(java.util.stream.Collectors.joining(", ")));
        r.setAttribute("importRoleLabels",roles);
    }
    static void download(HttpServletRequest r,HttpServletResponse s,String key,long actor,Access access) throws IOException {
        Object stored=r.getSession(false).getAttribute(key+"Report");
        if(!(stored instanceof ImportReport report))throw new IllegalArgumentException("Báo cáo không còn khả dụng. Hãy thực hiện lượt nhập mới.");
        byte[] bytes=report.workbook(actor,access);
        s.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        s.setHeader("Content-Disposition","attachment; filename="+(key.equals("userImport")?"bao-cao-nguoi-dung.xlsx":"bao-cao-san-pham.xlsx"));
        s.setHeader("Cache-Control","no-store");s.setHeader("X-Content-Type-Options","nosniff");
        s.getOutputStream().write(bytes);
    }
}
