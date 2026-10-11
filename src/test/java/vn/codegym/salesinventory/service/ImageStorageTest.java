package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
class ImageStorageTest {
    @TempDir Path root;
    @Test void cropsAndCreatesSquareAndThumbnailWithoutUsingClientFilename() throws Exception {
        BufferedImage original=new BufferedImage(300,100,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream stream=new ByteArrayOutputStream();ImageIO.write(original,"png",stream);
        var storage=new ImageStorage(root);String key=storage.save(stream.toByteArray());
        assertThat(ImageIO.read(storage.path(key,false).toFile()).getWidth()).isEqualTo(512);
        assertThat(ImageIO.read(storage.path(key,true).toFile()).getHeight()).isEqualTo(128);
        storage.remove(key);assertThat(Files.exists(storage.path(key,false))).isFalse();
    }
    @Test void refusesInvalidBytesOversizeAndPathTraversal() {
        var storage=new ImageStorage(root);
        assertThatThrownBy(() -> storage.save("not an image".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.save(new byte[ImageStorage.MAX_BYTES+1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.path("../../outside",false)).isInstanceOf(IllegalArgumentException.class);
    }
    byte[] image(String format) throws Exception {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(30,10,BufferedImage.TYPE_INT_RGB),format,output);return output.toByteArray();
    }
    @Test void acceptsJpegAndExactlyTwoMiBButRejectsEmptyAndGif() throws Exception {
        var storage=new ImageStorage(root);String jpeg=storage.save(image("jpeg"));
        assertThat(Files.exists(storage.path(jpeg,true))).isTrue();
        String padded=storage.save(java.util.Arrays.copyOf(image("png"),ImageStorage.MAX_BYTES));
        assertThat(Files.exists(storage.path(padded,false))).isTrue();
        assertThatThrownBy(()->storage.save(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->storage.save(image("gif"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void damagedPngIsInputErrorAndDoesNotLeaveFiles() throws Exception {
        var storage=new ImageStorage(root);byte[] png=image("png");
        assertThatThrownBy(()->storage.save(java.util.Arrays.copyOf(png,png.length/2))).isInstanceOf(IllegalArgumentException.class);
        try(var files=Files.list(root)){assertThat(files.count()).isZero();}
    }
    @Test void rejectsDecompressionBombBeforeDecodingPixels() throws Exception {
        byte[] png=image("png");var data=java.nio.ByteBuffer.wrap(png);data.putInt(16,6000);data.putInt(20,4000);
        var crc=new java.util.zip.CRC32();crc.update(png,12,17);data.putInt(29,(int)crc.getValue());
        assertThatThrownBy(()->new ImageStorage(root).save(png)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quá lớn");
    }
    @Test void squareUsesCenterNotLeftOrRightEdges() throws Exception {
        var original=new BufferedImage(300,100,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<100;y++)for(int x=0;x<300;x++)original.setRGB(x,y,x<100?0xff0000:x<200?0x00ff00:0x0000ff);
        var output=new ByteArrayOutputStream();ImageIO.write(original,"png",output);var storage=new ImageStorage(root);String key=storage.save(output.toByteArray());
        var square=ImageIO.read(storage.path(key,false).toFile());
        assertThat(square.getRGB(0,256)&0xffffff).isEqualTo(0x00ff00);assertThat(square.getRGB(511,256)&0xffffff).isEqualTo(0x00ff00);
    }
}
