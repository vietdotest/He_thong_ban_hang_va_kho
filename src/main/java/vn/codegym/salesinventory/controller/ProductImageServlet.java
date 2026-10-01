package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;import vn.codegym.salesinventory.service.*;import vn.codegym.salesinventory.dao.Sql;
public final class ProductImageServlet extends PortalServlet {
 protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{var product=new ProductService(source()).find(actor(r).id(),number(r,"id"));String key=Sql.text(product.get("image_key"));if(key.isEmpty()){s.sendError(404);return;}var path=ImageStorage.configured().path(key,!value(r,"size").equals("full"));if(!java.nio.file.Files.isRegularFile(path)){s.sendError(404);return;}s.setContentType("image/png");s.setHeader("Cache-Control","private, max-age=300");java.nio.file.Files.copy(path,s.getOutputStream());}
}
