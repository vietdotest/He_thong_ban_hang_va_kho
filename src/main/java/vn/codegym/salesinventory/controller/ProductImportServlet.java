package vn.codegym.salesinventory.controller;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import vn.codegym.salesinventory.service.*;
public final class ProductImportServlet extends PortalServlet {
    private final ProductImportService configured;
    public ProductImportServlet(){configured=null;}
    ProductImportServlet(ProductImportService configured){this.configured=configured;}
    private ProductImportService service(){return configured==null?new ProductImportService(source()):configured;}
    @Override protected void preparePost(HttpServletRequest r)throws Exception{
        String type=r.getContentType();if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))return;
        try{r.getPart("file");}catch(Exception failure){
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
                String name=cause.getClass().getName();
                if(name.contains(".fileupload.")&&(name.endsWith("SizeLimitExceededException")||name.endsWith("FileSizeLimitExceededException")))throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.",failure);
            }throw failure;
        }
    }
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("PRODUCT_MANAGE");
        if(value(r,"template").equals("1")){
            var sample=new ArrayList<>(List.of("SP-001","Sản phẩm mẫu","MA-NHOM","Cái","Thùng 12 cái","ACTIVE"));
            if(access(r).allows("COST_WRITE"))sample.add("10000");
            s.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");s.setHeader("Content-Disposition","attachment; filename=san-pham.xlsx");s.getOutputStream().write(Xlsx.write(List.of(ProductImportService.headers(access(r)),sample)));return;
        }render(r,s);
    }
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("PRODUCT_MANAGE");boolean confirm=value(r,"action").equals("confirm");
        try{
            if(confirm){
                var preview=(ImportPreview)r.getSession().getAttribute("productImport");if(preview==null)throw new IllegalArgumentException("Hãy tải lên tệp trước.");
                try{
                    var result=service().confirm(actor(r).id(),preview,value(r,"token"));r.setAttribute("report",result);
                    r.setAttribute("successCount",result.stream().filter(ImportPreview.Line::isValid).count());r.setAttribute("failureCount",result.stream().filter(line->!line.isValid()).count());
                }finally{r.getSession().removeAttribute("productImport");}
            }else{
                r.getSession().removeAttribute("productImport");var file=r.getPart("file");
                if(file==null||file.getSize()==0)throw new IllegalArgumentException("Vui lòng chọn tệp XLSX.");
                if(file.getSize()>Xlsx.MAX_BYTES)throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.");
                byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes(Xlsx.MAX_BYTES+1);}
                var preview=service().preview(actor(r).id(),bytes);r.getSession().setAttribute("productImport",preview);r.setAttribute("preview",preview);
                r.setAttribute("validCount",preview.getLines().stream().filter(ImportPreview.Line::isValid).count());r.setAttribute("invalidCount",preview.getLines().stream().filter(line->!line.isValid()).count());
            }
        }catch(IllegalArgumentException invalid){s.setStatus(400);r.setAttribute("errors",Map.of(confirm?"preview":"file",invalid.getMessage()));}
        render(r,s);
    }
    @Override protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message)throws ServletException,IOException{
        if(r.getSession(false)!=null)r.getSession(false).removeAttribute("productImport");s.setStatus(400);r.setAttribute("errors",Map.of("file",message));render(r,s);
    }
    private void render(HttpServletRequest r,HttpServletResponse s)throws ServletException,IOException{
        r.setAttribute("title","Nhập sản phẩm Excel");r.setAttribute("headers",ProductImportService.headers(access(r)));view(r,s,"imports/import");
    }
}
