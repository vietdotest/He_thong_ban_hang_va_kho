package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;

/** Production import flow. Sessions store only the task UUID, never preview rows or results. */
public abstract class DurableImportServlet extends PortalServlet {
    protected abstract String importKind();
    protected abstract ImportPreview readPreview(long actor,byte[] bytes);
    protected ImportJobService jobs(){return (ImportJobService)getServletContext().getAttribute(ApplicationContextKeys.IMPORT_JOB_SERVICE);}
    private String path(){return importKind().equals("USER")?"/admin/users/import":"/catalog/products/import";}
    private String key(){return importKind().equals("USER")?"userImportJobId":"productImportJobId";}
    private String permission(){return importKind().equals("USER")?"USER_MANAGE":"PRODUCT_MANAGE";}
    private String jobId(HttpServletRequest r){String id=value(r,"job");if(id.isEmpty()){Object saved=r.getSession().getAttribute(key());id=saved instanceof String?(String)saved:"";}if(!id.isEmpty()&&!id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException("Mã tác vụ không hợp lệ.");return id;}
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require(permission());
        if(value(r,"new").equals("1"))r.getSession().removeAttribute(key());
        if(value(r,"template").equals("1")){
            List<String> headers;List<String> sample;
            if(importKind().equals("USER")){headers=UserImportService.HEADERS;sample=List.of("nhanvien01","nhanvien@example.com","Nguyễn Văn An","0901234567","SALES","","");}
            else{headers=ProductImportService.headers(access(r));sample=new ArrayList<>(List.of("SP-001","Sản phẩm mẫu","MA-NHOM","Cái","Thùng 12 cái","ACTIVE"));if(access(r).allows("COST_WRITE"))sample.add("10000");}
            download(s,importKind().equals("USER")?"nguoi-dung.xlsx":"san-pham.xlsx",Xlsx.write(List.of(headers,sample)));return;
        }
        String id=jobId(r);
        if(value(r,"report").equals("1")){if(id.isEmpty())throw new IllegalArgumentException("Hãy chọn tác vụ trước.");download(s,"bao-cao-nhap.xlsx",jobs().report(actor(r).id(),importKind(),id));return;}
        if(value(r,"progress").equals("1")){if(id.isEmpty())throw new IllegalArgumentException("Hãy chọn tác vụ trước.");
            var dto=new LinkedHashMap<>(jobs().progress(actor(r).id(),importKind(),id));
            var rows=jobs().rows(actor(r).id(),importKind(),id,value(r,"q"),value(r,"state"),PageRequest.parse(value(r,"page"),value(r,"pageSize")));
            dto.put("rows",rows.items());dto.put("page",rows.page());dto.put("pageSize",rows.pageSize());dto.put("totalItems",rows.totalItems());dto.put("totalPages",rows.getTotalPages());
            s.setContentType("application/json;charset=UTF-8");s.setHeader("Cache-Control","no-store");new ObjectMapper().writeValue(s.getWriter(),dto);return;}
        show(r,s,id);
    }
    private static void download(HttpServletResponse s,String name,byte[] bytes)throws IOException{s.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");s.setHeader("Content-Disposition","attachment; filename="+name);s.setHeader("Cache-Control","no-store");s.getOutputStream().write(bytes);}
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require(permission());String id=jobId(r);boolean confirming=value(r,"action").equals("confirm");
        try{
            if(confirming){if(id.isEmpty())throw new IllegalArgumentException("Hãy tải lên tệp trước.");jobs().confirm(actor(r).id(),importKind(),id,number(r,"version"),value(r,"token"));}
            else{var file=r.getPart("file");if(file==null||file.getSize()==0)throw new IllegalArgumentException("Vui lòng chọn tệp XLSX.");if(file.getSize()>Xlsx.MAX_BYTES)throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.");byte[] bytes;try(var input=file.getInputStream()){bytes=input.readNBytes(Xlsx.MAX_BYTES+1);}id=jobs().create(actor(r).id(),importKind(),file.getSubmittedFileName(),readPreview(actor(r).id(),bytes));}
            r.getSession().setAttribute(key(),id);
            // Clear any legacy in-memory state when upgrading an existing session.
            String old=importKind().equals("USER")?"userImport":"productImport";
            for(String suffix:List.of("","Report","File"))r.getSession().removeAttribute(old+suffix);
            StringBuilder url=new StringBuilder(path()).append("?job=").append(id);
            if(confirming)for(String filter:List.of("q","state","page","pageSize"))if(!value(r,filter).isEmpty())url.append('&').append(filter).append('=').append(URLEncoder.encode(value(r,filter),StandardCharsets.UTF_8));
            redirect(r,s,url.toString());
        }catch(IllegalArgumentException invalid){s.setStatus(400);r.setAttribute("errors",Map.of(confirming?"preview":"file",invalid.getMessage()));show(r,s,confirming?id:"");}
    }
    private void show(HttpServletRequest r,HttpServletResponse s,String id)throws ServletException,IOException{
        r.setAttribute("userImportPage",importKind().equals("USER"));r.setAttribute("title",importKind().equals("USER")?"Nhập người dùng Excel":"Nhập sản phẩm Excel");r.setAttribute("importPath",path());r.setAttribute("importStep",1);
        if(!id.isEmpty()){
            var job=jobs().find(actor(r).id(),importKind(),id);var rows=jobs().rows(actor(r).id(),importKind(),id,value(r,"q"),value(r,"state"),PageRequest.parse(value(r,"page"),value(r,"pageSize")));
            r.setAttribute("job",job);r.setAttribute("pagination",rows);r.setAttribute("rows",rows.items());r.setAttribute("headers",job.get("headers"));r.setAttribute("importStep",job.get("status").equals("PREVIEW")?2:3);
        }
        view(r,s,"imports/job");
    }
    @Override protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message)throws ServletException,IOException{s.setStatus(400);r.setAttribute("errors",Map.of("file",message));show(r,s,"");}
}
