package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
public final class UserImportServlet extends PortalServlet {
    private UserImportService service(){return new UserImportService(source(),(UserManagementService)getServletContext().getAttribute(ApplicationContextKeys.USER_MANAGEMENT_SERVICE));}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{if(value(r,"template").equals("1")){s.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");s.setHeader("Content-Disposition","attachment; filename=nguoi-dung.xlsx");s.getOutputStream().write(Xlsx.write(List.of(UserImportService.HEADERS,List.of("nhanvien01","nhanvien@example.com","Nguyễn Văn An","0901234567","SALES","",""))));return;}render(r,s);}
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{if(value(r,"action").equals("confirm")){var preview=(ImportPreview)r.getSession().getAttribute("userImport");if(preview==null)throw new IllegalArgumentException("Hãy tải lên tệp trước.");var result=service().confirm(actor(r).id(),preview,value(r,"token"),RequestMetadata.authenticationContext(r));r.getSession().removeAttribute("userImport");r.setAttribute("report",result);r.setAttribute("successCount",result.stream().filter(ImportPreview.Line::isValid).count());r.setAttribute("failureCount",result.stream().filter(line->!line.isValid()).count());}else{var file=r.getPart("file");if(file==null||file.getSize()>Xlsx.MAX_BYTES)throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.");var preview=service().preview(actor(r).id(),file.getInputStream().readAllBytes());r.getSession().setAttribute("userImport",preview);r.setAttribute("preview",preview);}render(r,s);}
    private void render(HttpServletRequest r,HttpServletResponse s)throws Exception{r.setAttribute("title","Nhập người dùng Excel");r.setAttribute("headers",UserImportService.HEADERS);view(r,s,"imports/import");}
}
