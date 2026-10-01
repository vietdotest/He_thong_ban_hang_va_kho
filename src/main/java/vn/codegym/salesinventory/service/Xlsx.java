package vn.codegym.salesinventory.service;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.*;
/** XLSX một bảng: giới hạn cả dữ liệu nén, dữ liệu giải nén và số ô. */
public final class Xlsx {
    public static final int MAX_BYTES=10*1024*1024, MAX_ROWS=10000;
    public record Row(int number,List<String> cells,String error) {}
    private Xlsx() {}
    private static XMLStreamReader xml(byte[] data) throws Exception {
        XMLInputFactory f=XMLInputFactory.newFactory();f.setProperty(XMLInputFactory.SUPPORT_DTD,false);f.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        f.setXMLResolver((a,b,c,d)->{throw new XMLStreamException("Không nhận tài nguyên ngoài.");});
        return f.createXMLStreamReader(new ByteArrayInputStream(data),"UTF-8");
    }
    private static int next(XMLStreamReader x) throws Exception {int e=x.next();if(e==XMLStreamConstants.DTD||e==XMLStreamConstants.ENTITY_REFERENCE)throw new IllegalArgumentException("XML không hợp lệ.");return e;}
    private static String attr(XMLStreamReader x,String name){for(int i=0;i<x.getAttributeCount();i++)if(name.equals(x.getAttributeLocalName(i)))return x.getAttributeValue(i);return "";}
    public static List<Row> read(byte[] data) {
        if(data.length>MAX_BYTES)throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.");
        try {
            Map<String,byte[]> entries=new HashMap<>();int expanded=0;
            try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(data))){ZipEntry e;while((e=z.getNextEntry())!=null){
                String name=e.getName();if(name.contains("..")||name.startsWith("/")||name.contains("\\")||entries.containsKey(name)||entries.size()>100)throw new IllegalArgumentException("Cấu trúc XLSX không hợp lệ.");
                var b=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;while((n=z.read(chunk))!=-1){expanded+=n;if(expanded>50*1024*1024)throw new IllegalArgumentException("Tệp giải nén quá lớn.");b.write(chunk,0,n);}entries.put(name,b.toByteArray());}}
            if(!entries.containsKey("xl/workbook.xml"))throw new IllegalArgumentException("Phải dùng tệp XLSX.");
            String rid="";var w=xml(entries.get("xl/workbook.xml"));while(w.hasNext()){if(next(w)==1&&w.getLocalName().equals("sheet")){rid=attr(w,"id");break;}}w.close();
            String path="";var rel=xml(entries.getOrDefault("xl/_rels/workbook.xml.rels",new byte[0]));while(rel.hasNext()){if(next(rel)==1&&rel.getLocalName().equals("Relationship")&&attr(rel,"Id").equals(rid)){String target=attr(rel,"Target");if(!attr(rel,"TargetMode").isEmpty()||target.contains("..")||target.contains("\\"))throw new IllegalArgumentException("Liên kết bảng không hợp lệ.");path=target.startsWith("/")?target.substring(1):"xl/"+target;}}rel.close();
            if(!entries.containsKey(path))throw new IllegalArgumentException("Không tìm thấy bảng đầu tiên.");
            List<String> strings=new ArrayList<>();if(entries.containsKey("xl/sharedStrings.xml")){var x=xml(entries.get("xl/sharedStrings.xml"));StringBuilder s=null;while(x.hasNext()){int e=next(x);if(e==1&&x.getLocalName().equals("si"))s=new StringBuilder();else if(e==1&&x.getLocalName().equals("t")&&s!=null)s.append(x.getElementText());else if(e==2&&x.getLocalName().equals("si")){if(s.length()>10000||strings.size()>200000)throw new IllegalArgumentException("Quá nhiều dữ liệu ô.");strings.add(s.toString());}}x.close();}
            List<Row> rows=new ArrayList<>();var x=xml(entries.get(path));List<String> cells=null;int rowNo=0,col=0;String type="",error="";StringBuilder value=null;
            while(x.hasNext()){int e=next(x);if(e==1){switch(x.getLocalName()){
                case "row" -> {rowNo=attr(x,"r").isEmpty()?rowNo+1:Integer.parseInt(attr(x,"r"));if(rows.size()>MAX_ROWS||rowNo>MAX_ROWS+1)throw new IllegalArgumentException("Tối đa 10.000 dòng dữ liệu.");cells=new ArrayList<>();error="";}
                case "c" -> {if(cells==null)throw new IllegalArgumentException("Ô ngoài dòng.");String ref=attr(x,"r");col=0;for(char ch:ref.toCharArray()){if(ch<'A'||ch>'Z')break;col=col*26+ch-'A'+1;}col=ref.isEmpty()?cells.size():col-1;if(col<0||col>31)throw new IllegalArgumentException("Tối đa 32 cột.");while(cells.size()<=col)cells.add("");type=attr(x,"t");value=new StringBuilder();}
                case "f" -> error="Không nhận công thức; hãy dùng giá trị.";
                case "v","t" -> {String v=x.getElementText();if(v.length()>10000)throw new IllegalArgumentException("Giá trị ô quá dài.");if(value!=null)value.append(v);}
            }}else if(e==2&&x.getLocalName().equals("c")){String v=value.toString();if(type.equals("s"))v=strings.get(Integer.parseInt(v));cells.set(col,v.trim());}
            else if(e==2&&x.getLocalName().equals("row")){rows.add(new Row(rowNo,List.copyOf(cells),error));cells=null;}}x.close();return List.copyOf(rows);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Không đọc được XLSX. Hãy tải tệp mẫu và kiểm tra nội dung.");}
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    public static byte[] write(List<List<String>> rows){
        try{var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){
            entry(z,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            entry(z,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(z,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Du lieu\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(z,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            StringBuilder s=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");int n=0;for(var row:rows){s.append("<row r=\"").append(++n).append("\">");for(String cell:row)s.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(cell)).append("</t></is></c>");s.append("</row>");}s.append("</sheetData></worksheet>");entry(z,"xl/worksheets/sheet1.xml",s.toString());}return out.toByteArray();
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    private static void entry(ZipOutputStream z,String name,String text)throws IOException{z.putNextEntry(new ZipEntry(name));z.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8));z.closeEntry();}
}
