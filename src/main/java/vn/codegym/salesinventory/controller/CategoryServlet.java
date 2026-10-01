package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;import vn.codegym.salesinventory.service.*;import vn.codegym.salesinventory.dao.Sql;
public final class CategoryServlet extends PortalServlet {
 protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{r.setAttribute("categories",new CategoryService(source()).tree());if(!value(r,"id").isEmpty())r.setAttribute("edit",Sql.transaction(source(),c->Sql.one(c,"SELECT id,code,name,parent_id,version FROM categories WHERE id=?",number(r,"id"))));view(r,s,"catalog/categories");}
 protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{var service=new CategoryService(source());long id=value(r,"id").isEmpty()?0:number(r,"id");if(value(r,"action").equals("delete"))service.delete(actor(r).id(),id);else service.save(actor(r).id(),id,value(r,"code"),value(r,"name"),value(r,"parent").isEmpty()?null:number(r,"parent"),value(r,"version").isEmpty()?0:number(r,"version"));redirect(r,s,"/catalog/categories");}
}
