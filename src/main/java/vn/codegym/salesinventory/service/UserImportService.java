package vn.codegym.salesinventory.service;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.validation.*;
public final class UserImportService {
    public static final List<String> HEADERS=List.of("Tên đăng nhập","Email","Họ tên","Điện thoại","Vai trò","Kho","Địa bàn");
    private final DataSource source;private final UserManagementService users;
    public UserImportService(DataSource source,UserManagementService users){this.source=source;this.users=users;}
    private record Input(UserAccountCommand command,Set<String> roles,Set<Long> warehouses,Set<Long> territories) {}
    private static String cell(List<String> row,int i){return row.size()>i?row.get(i):"";}
    private static Set<String> codes(String text){Set<String> result=new LinkedHashSet<>();for(String s:text.split(","))if(!s.isBlank())result.add(s.trim());return result;}
    private Input validate(List<String> row) {
        String phone=PhoneNumber.normalize(cell(row,3));Set<String> roles=codes(cell(row,4));
        var command=new UserAccountCommand(cell(row,0),cell(row,1),cell(row,2),phone,roles.stream().findFirst().orElse(""),UserStatus.PENDING_ACTIVATION,0);
        var errors=new UserAccountValidator().validate(command);if(!errors.isEmpty())throw new IllegalArgumentException(String.join(" ",errors.values()));
        return Sql.transaction(source,c->{for(String role:roles)Sql.one(c,"SELECT id FROM roles WHERE code=?",role);Set<Long> warehouses=new LinkedHashSet<>(),territories=new LinkedHashSet<>();for(String code:codes(cell(row,5)))warehouses.add(Sql.id(Sql.one(c,"SELECT id FROM warehouses WHERE code=?",code).get("id")));for(String code:codes(cell(row,6)))territories.add(Sql.id(Sql.one(c,"SELECT id FROM territories WHERE code=?",code).get("id")));AssignmentService.validate(-1,-2,roles,warehouses);
            if(!Sql.query(c,"SELECT id FROM users WHERE username=? OR email=? OR phone_normalized=?",command.username(),command.email(),phone).isEmpty())throw new IllegalArgumentException("Tên đăng nhập, email hoặc điện thoại đã được dùng.");return new Input(command,roles,warehouses,territories);});
    }
    public ImportPreview preview(long actor,byte[] bytes){new AccessService(source).load(actor).require("USER_MANAGE");var rows=Xlsx.read(bytes);if(rows.isEmpty()||!rows.get(0).cells().equals(HEADERS))throw new IllegalArgumentException("Tiêu đề không đúng tệp mẫu.");List<ImportPreview.Line> lines=new ArrayList<>();Set<String> names=new HashSet<>(),emails=new HashSet<>(),phones=new HashSet<>();for(var row:rows.subList(1,rows.size())){if(row.cells().stream().allMatch(String::isBlank))continue;String error=row.error();try{if(!error.isEmpty())throw new IllegalArgumentException(error);var in=validate(row.cells());if(!names.add(in.command.username().toLowerCase(Locale.ROOT))||!emails.add(in.command.email().toLowerCase(Locale.ROOT))||!phones.add(in.command.phone()))throw new IllegalArgumentException("Dòng trùng trong tệp.");}catch(IllegalArgumentException e){error=e.getMessage();}lines.add(new ImportPreview.Line(row.number(),row.cells(),"Tạo mới",error));}return new ImportPreview(actor,lines,Instant.now());}
    public List<ImportPreview.Line> confirm(long actor,ImportPreview preview,String token,AuthenticationContext context){new AccessService(source).load(actor).require("USER_MANAGE");preview.claim(actor,token,Instant.now());List<ImportPreview.Line> result=new ArrayList<>();for(var row:preview.getLines()){if(!row.isValid()){result.add(row);continue;}String error="";try{var in=validate(row.cells());var saved=users.createAssigned(in.command,actor,context,in.roles,in.warehouses,in.territories);if(saved.status()!=UserManagementResult.Status.SUCCESS)error=message(saved.status());}catch(RuntimeException e){error=e instanceof IllegalArgumentException?e.getMessage():"Không thể nhập dòng này. Kiểm tra email và dữ liệu.";}result.add(new ImportPreview.Line(row.number(),row.cells(),"Tạo mới",error));}return result;}
    private static String message(UserManagementResult.Status s){return switch(s){case EMAIL_DELIVERY_FAILED->"Gửi email thất bại, chưa tạo tài khoản.";case FORBIDDEN->"Quyền quản trị đã thay đổi.";case DUPLICATE_USERNAME,DUPLICATE_EMAIL,DUPLICATE_PHONE->"Dữ liệu trùng với tài khoản hiện có.";default->"Không thể tạo tài khoản.";};}
}
