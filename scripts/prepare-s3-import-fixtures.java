import java.nio.file.*;
import java.util.*;
import vn.codegym.salesinventory.service.*;

/** Run Java17 with -Dfile.encoding=UTF-8. Synthetic workbooks only; no database access. */
class S3ImportFixtures {
    public static void main(String[] args)throws Exception{
        if(args.length!=1||!args[0].matches("qa-[a-z0-9-]{5,30}"))throw new IllegalArgumentException("Cần prefix QA duy nhất, không dùng dữ liệu thật.");
        String prefix=args[0];Path output=Path.of(".tools","s3-evidence");Files.createDirectories(output);
        var productHeaders=new ArrayList<>(ProductImportService.HEADERS);productHeaders.add("Giá vốn");
        var products=new ArrayList<List<String>>();products.add(productHeaders);
        for(int i=0;i<5000;i++)products.add(List.of(prefix+"-p-"+String.format("%04d",i),"Sản phẩm nhập QA "+i,"S3-UX","Lon","Thùng 24 lon","ACTIVE","12.0001"));
        Files.write(output.resolve("import-products-5000.xlsx"),Xlsx.write(products));
        var small=new ArrayList<List<String>>();small.add(productHeaders);for(int i=0;i<22;i++)small.add(List.of(prefix+"-s-"+i,"Sản phẩm xem trước "+i,"S3-UX","Lon","","ACTIVE","23.4567"));small.add(List.of(prefix+"-invalid","Dòng lỗi QA","KHONG-TON-TAI","Lon","","ACTIVE","1"));
        Files.write(output.resolve("import-products-mixed.xlsx"),Xlsx.write(small));
        var users=new ArrayList<List<String>>();users.add(UserImportService.HEADERS);
        // UTC+7 clock is irrelevant to synthetic IDs; caller supplies a unique prefix.
        int sequence=Math.floorMod(prefix.hashCode(),5000)*5000+40000000;
        for(int i=0;i<5000;i++)users.add(List.of(prefix+"-u-"+i,prefix+"-u-"+i+"@test.local","Tài khoản nhập QA "+i,"09"+String.format("%08d",sequence+i),"SALES","",""));
        Files.write(output.resolve("import-users-5000.xlsx"),Xlsx.write(users));
        System.out.println("Đã tạo XLSX QA: 23 dòng hỗn hợp và hai tệp 5.000 dòng; không có mật khẩu/token.");
    }
}
