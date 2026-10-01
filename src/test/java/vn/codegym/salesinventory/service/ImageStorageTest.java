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
}
