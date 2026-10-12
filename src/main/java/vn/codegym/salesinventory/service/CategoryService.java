package vn.codegym.salesinventory.service;

import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.FieldValidationException;

public final class CategoryService {
    private final DataSource source;
    public CategoryService(DataSource source) { this.source=source; }
    public List<Map<String,Object>> tree() {
        return Sql.snapshot(source,c->{
            var all=Sql.query(c,"SELECT id,code,name,parent_id,version FROM categories ORDER BY name,id");
            return orderedTree(all);
        });
    }
    /** Search preserves ancestors and the entire matching branch, never a flat page. */
    public List<Map<String,Object>> tree(long actor,String query) {
        String q=query==null?"":query.trim();if(q.length()>150)q=q.substring(0,150);String pattern="%"+q+"%";
        return Sql.snapshot(source,c->{DealerService.access(c,actor,"CATALOG_READ");
            var all=qIsEmpty(pattern)?Sql.query(c,"SELECT id,code,name,parent_id,version FROM categories ORDER BY name,id"):
                Sql.query(c,"WITH RECURSIVE matched AS (SELECT id,parent_id FROM categories WHERE code LIKE ? OR name LIKE ?), ancestors AS (SELECT id,parent_id FROM matched UNION DISTINCT SELECT c.id,c.parent_id FROM categories c JOIN ancestors a ON c.id=a.parent_id), descendants AS (SELECT id,parent_id FROM matched UNION DISTINCT SELECT c.id,c.parent_id FROM categories c JOIN descendants d ON c.parent_id=d.id) SELECT id,code,name,parent_id,version FROM categories WHERE id IN (SELECT id FROM ancestors UNION SELECT id FROM descendants) ORDER BY name,id",pattern,pattern);
            return orderedTree(all);});
    }
    private static boolean qIsEmpty(String pattern){return pattern.equals("%%");}
    private static List<Map<String,Object>> orderedTree(List<Map<String,Object>> all) {
        var children=new HashMap<Long,List<Map<String,Object>>>();
        for(var row:all){Long parent=row.get("parent_id")==null?null:Sql.id(row.get("parent_id"));children.computeIfAbsent(parent,key->new ArrayList<>()).add(row);}
        var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();visit(children,null,0,result,seen);
        if(seen.size()!=all.size())throw new IllegalStateException("Cây nhóm hàng không hợp lệ.");return result;
    }
    private static void visit(Map<Long,List<Map<String,Object>>> children,Long parent,int depth,List<Map<String,Object>> out,Set<Long> seen) {
        if(depth>30)throw new IllegalStateException("Cây nhóm hàng vượt quá 30 cấp.");
        for(var row:children.getOrDefault(parent,List.of())) {
                long id=Sql.id(row.get("id"));if(!seen.add(id))throw new IllegalStateException("Cây nhóm hàng không hợp lệ.");
                var item=new LinkedHashMap<>(row);item.put("depth",depth);item.put("label","— ".repeat(depth)+row.get("name"));
                out.add(item);visit(children,id,depth+1,out,seen);
        }
    }
    public Map<String,Object> find(long actor,long id) {
        return Sql.transaction(source,c->{DealerService.access(c,actor,"CATALOG_READ");return Sql.one(c,"SELECT id,code,name,parent_id,version FROM categories WHERE id=?",id);});
    }
    public static Map<String,String> errors(String code,String name,Long parent,long version) {
        var errors=new LinkedHashMap<String,String>();
        if(code==null||!code.trim().matches("[A-Za-z0-9_-]{1,50}"))errors.put("code","Mã nhóm gồm 1–50 chữ, số, dấu gạch ngang hoặc gạch dưới.");
        if(name==null||name.trim().length()<2||name.trim().length()>150)errors.put("name","Tên nhóm phải có từ 2 đến 150 ký tự.");
        if(parent!=null&&parent<=0)errors.put("parent","Nhóm cha không hợp lệ.");
        if(version<0)errors.put("form","Phiên bản nhóm không hợp lệ.");
        return errors;
    }
    private static int height(List<Map<String,Object>> all,long id,Set<Long> seen) {
        if(!seen.add(id))throw FieldValidationException.field("parent","Không thể tạo vòng lặp trong cây nhóm.");
        int height=0;
        for(var row:all)if(row.get("parent_id")!=null&&Sql.id(row.get("parent_id"))==id)
            height=Math.max(height,1+height(all,Sql.id(row.get("id")),seen));
        return height;
    }
    public long save(long actor,long id,String code,String name,Long parent,long version) {
        var errors=errors(code,name,parent,version);if(id<0)errors.put("form","Mã nhóm không hợp lệ.");
        if(!errors.isEmpty())throw new FieldValidationException(errors);
        String normalizedCode=code.trim(),normalizedName=name.trim();
        return Sql.transaction(source,c->{
            DealerService.access(c,actor,"PRODUCT_MANAGE");
            Sql.one(c,"SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR UPDATE");
            Map<String,Object> before=null;
            if(id!=0) {
                var found=Sql.query(c,"SELECT id,code,name,parent_id,version FROM categories WHERE id=? FOR UPDATE",id);
                if(found.isEmpty())throw FieldValidationException.field("form","Nhóm không còn tồn tại.");
                before=found.get(0);
                if(Sql.id(before.get("version"))!=version)throw FieldValidationException.field("form","Nhóm đã thay đổi. Hãy tải lại.");
            }
            Long ancestor=parent;var seen=new HashSet<Long>();int depth=0;
            while(ancestor!=null) {
                if(ancestor==id||!seen.add(ancestor))throw FieldValidationException.field("parent","Không thể tạo vòng lặp trong cây nhóm.");
                var found=Sql.query(c,"SELECT parent_id FROM categories WHERE id=?",ancestor);
                if(found.isEmpty())throw FieldValidationException.field("parent","Nhóm cha không còn tồn tại.");
                ancestor=found.get(0).get("parent_id")==null?null:Sql.id(found.get(0).get("parent_id"));
                if(++depth>30)throw FieldValidationException.field("parent","Cây nhóm tối đa 30 cấp.");
            }
            if(id!=0&&depth+height(Sql.query(c,"SELECT id,parent_id FROM categories"),id,new HashSet<>())>30)
                throw FieldValidationException.field("parent","Chuyển cả nhánh sẽ vượt quá 30 cấp.");
            if(!Sql.query(c,"SELECT id FROM categories WHERE code=? AND id<>?",normalizedCode,id).isEmpty())
                throw FieldValidationException.field("code","Mã nhóm đã tồn tại.");
            long saved=id;
            try {
                if(id==0)saved=Sql.insert(c,"INSERT INTO categories(code,name,parent_id) VALUES(?,?,?)",normalizedCode,normalizedName,parent);
                else Sql.update(c,"UPDATE categories SET code=?,name=?,parent_id=?,version=version+1 WHERE id=?",normalizedCode,normalizedName,parent,id);
            } catch(java.sql.SQLException failure) {
                String message=String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
                if(failure.getErrorCode()==1062&&"23000".equals(failure.getSQLState())&&(message.contains("'code'")||message.contains("'categories.code'")))
                    throw FieldValidationException.field("code","Mã nhóm đã tồn tại.");
                throw failure;
            }
            AuditService.record(c,actor,"CATEGORY_SAVED","CATEGORY",saved,before,Sql.one(c,"SELECT id,code,name,parent_id,version FROM categories WHERE id=?",saved));
            return saved;
        });
    }
    public void delete(long actor,long id) {
        Sql.transaction(source,c->{
            DealerService.access(c,actor,"PRODUCT_MANAGE");
            Sql.one(c,"SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR UPDATE");
            var before=Sql.one(c,"SELECT id,code,name,parent_id FROM categories WHERE id=? FOR UPDATE",id);
            if(!Sql.query(c,"SELECT id FROM categories WHERE parent_id=? LIMIT 1",id).isEmpty())throw FieldValidationException.field("form","Nhóm còn nhóm con, không thể xóa.");
            if(!Sql.query(c,"SELECT id FROM products WHERE category_id=? LIMIT 1",id).isEmpty())throw FieldValidationException.field("form","Nhóm còn sản phẩm, không thể xóa.");
            if(!Sql.query(c,"SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='discount_policies'").isEmpty()&&!Sql.query(c,"SELECT id FROM discount_policies WHERE category_id=? LIMIT 1",id).isEmpty())throw FieldValidationException.field("form","Nhóm đã có chính sách chiết khấu, không thể xóa để bảo toàn lịch sử.");
            Sql.update(c,"DELETE FROM categories WHERE id=?",id);AuditService.record(c,actor,"CATEGORY_DELETED","CATEGORY",id,before,null);return null;
        });
    }
}
