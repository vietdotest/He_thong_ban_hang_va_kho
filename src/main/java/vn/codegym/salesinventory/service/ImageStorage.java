package vn.codegym.salesinventory.service;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
public final class ImageStorage {
    public static final int MAX_BYTES=2*1024*1024;
    private final Path root;
    public ImageStorage(Path root) { this.root=root.toAbsolutePath().normalize(); }
    public static ImageStorage configured() {
        String configured=System.getenv("APP_UPLOAD_DIR");
        return new ImageStorage(configured==null || configured.isBlank() ? Path.of(System.getProperty("catalina.base",System.getProperty("user.dir")),"uploads") : Path.of(configured));
    }
    public String save(byte[] bytes) throws IOException {
        if(bytes.length==0 || bytes.length>MAX_BYTES)throw new IllegalArgumentException("Ảnh JPG/PNG phải có dung lượng tối đa 2MB.");
        BufferedImage image;
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("Tệp không phải ảnh JPG/PNG hợp lệ.");
            var reader=readers.next();
            try {
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("jpeg","jpg","png").contains(format))throw new IllegalArgumentException("Chỉ chấp nhận ảnh JPG/PNG.");
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || (long)width*height>20_000_000L)throw new IllegalArgumentException("Kích thước ảnh quá lớn.");
                image=reader.read(0);
                if(image==null)throw new IllegalArgumentException("Tệp ảnh bị hỏng hoặc không đọc được.");
            } catch(javax.imageio.IIOException invalid) {
                throw new IllegalArgumentException("Tệp ảnh bị hỏng hoặc không đọc được.",invalid);
            } finally { reader.dispose(); }
        }
        String key=UUID.randomUUID().toString();Files.createDirectories(root);
        try { writeSquare(image,512,path(key,false));writeSquare(image,128,path(key,true));return key; }
        catch(IOException | RuntimeException e) { remove(key);throw e; }
    }
    private void writeSquare(BufferedImage source,int size,Path destination) throws IOException {
        int crop=Math.min(source.getWidth(),source.getHeight());int x=(source.getWidth()-crop)/2,y=(source.getHeight()-crop)/2;
        BufferedImage output=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D g=output.createGraphics();
        try { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,size,size,x,y,x+crop,y+crop,null); } finally { g.dispose(); }
        Path temporary=Files.createTempFile(root,"image-",".tmp");
        try { if(!ImageIO.write(output,"png",temporary.toFile()))throw new IOException("Không ghi được ảnh.");Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING); } finally { Files.deleteIfExists(temporary); }
    }
    public Path path(String key,boolean thumbnail) {
        if(key==null || !key.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Ảnh không hợp lệ.");
        Path p=root.resolve(key+(thumbnail?"-small":"")+".png").normalize();if(!p.startsWith(root))throw new IllegalArgumentException("Đường dẫn ảnh không hợp lệ.");return p;
    }
    public void remove(String key) throws IOException { if(key!=null && !key.isBlank()) {Files.deleteIfExists(path(key,false));Files.deleteIfExists(path(key,true));} }
}
