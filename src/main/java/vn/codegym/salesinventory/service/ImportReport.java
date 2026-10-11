package vn.codegym.salesinventory.service;

import java.util.ArrayList;
import java.util.List;
import vn.codegym.salesinventory.security.Access;

/** Kết quả của một lượt nhập; chỉ dùng trong phiên của người đã nhập. */
public final class ImportReport {
    private final long owner;
    private final boolean users;
    private final String filename;
    private final List<String> headers;
    private final List<ImportPreview.Line> lines;

    public ImportReport(long owner,boolean users,String filename,List<String> headers,List<ImportPreview.Line> lines) {
        this.owner=owner;this.users=users;this.filename=filename;this.headers=List.copyOf(headers);
        this.lines=lines.stream().map(line -> new ImportPreview.Line(line.number(),
                line.cells().subList(0,Math.min(headers.size(),line.cells().size())),line.operation(),line.error())).toList();
    }
    private void require(long actor,Access access) {
        access.require(users?"USER_MANAGE":"PRODUCT_MANAGE");
        if(actor!=owner)throw new SecurityException("Báo cáo thuộc phiên của người nhập.");
    }
    public String getFilename() {return filename;}
    public List<String> headers(long actor,Access access) {
        require(actor,access);
        return headers.stream().filter(h->!h.equals("Giá vốn")||access.allows("COST_READ")).toList();
    }
    public List<ImportPreview.Line> lines(long actor,Access access) {
        require(actor,access);
        int cost=headers.indexOf("Giá vốn");
        if(cost<0||access.allows("COST_READ"))return lines;
        return lines.stream().map(line -> {
            var cells=new ArrayList<>(line.cells());
            if(cost<cells.size())cells.remove(cost);
            return new ImportPreview.Line(line.number(),cells,line.operation(),line.error());
        }).toList();
    }
    public byte[] workbook(long actor,Access access) {
        List<String> visibleHeaders=headers(actor,access);
        var heading=new ArrayList<String>();heading.add("Dòng");heading.addAll(visibleHeaders);heading.addAll(List.of("Thao tác","Kết quả","Lỗi"));
        var rows=new ArrayList<List<String>>();rows.add(heading);
        for(var line:lines(actor,access)) {
            var row=new ArrayList<String>();row.add(Integer.toString(line.number()));
            for(int i=0;i<visibleHeaders.size();i++)row.add(i<line.cells().size()?line.cells().get(i):"");
            row.add(line.operation());row.add(line.isValid()?"Đã nhập":"Bỏ qua");row.add(line.error());rows.add(row);
        }
        return Xlsx.write(rows);
    }
}
