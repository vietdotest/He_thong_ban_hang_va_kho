package vn.codegym.salesinventory.dao;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarAcceptanceIT extends StoryDatabaseSupport {
    @TempDir Path root;
    ImageStorage images;
    AvatarService avatars;
    long actor;
    @BeforeEach void fixture() { actor=user("SALES");images=new ImageStorage(root);avatars=new AvatarService(source,images); }
    byte[] png() throws IOException {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(60,20,BufferedImage.TYPE_INT_RGB),"png",bytes);return bytes.toByteArray();
    }
    String key() { return Sql.text(one("SELECT avatar_key FROM users WHERE id=?",actor).get("avatar_key")); }
    long files() throws IOException { try(var stream=Files.list(root)){return stream.count();} }
    @Test void savesOnlyOwnersImageAndCorrectAudit() throws Exception {
        long other=user("SALES");String key=avatars.replace(actor,png());
        assertThat(key()).isEqualTo(key);assertThat(one("SELECT avatar_key FROM users WHERE id=?",other).get("avatar_key")).isNull();
        assertThat(ImageIO.read(images.path(key,false).toFile()).getWidth()).isEqualTo(512);
        assertThat(ImageIO.read(images.path(key,true).toFile()).getHeight()).isEqualTo(128);
        var audit=one("SELECT * FROM audit_logs WHERE actor_user_id=? AND event_type='AVATAR_UPDATED'",actor);
        assertThat(Sql.id(audit.get("object_id"))).isEqualTo(actor);assertThat(audit.get("after_values").toString()).contains(key);
    }
    @Test void removesOldFilesOnlyAfterCommittedReplacement() throws Exception {
        String old=avatars.replace(actor,png());var spy=spy(images);
        doAnswer(call->{assertThat(key()).isNotEqualTo(old);assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE actor_user_id=? AND event_type='AVATAR_UPDATED'",actor)).isEqualTo(2);return call.callRealMethod();}).when(spy).remove(old);
        String next=new AvatarService(source,spy).replace(actor,png());
        assertThat(Files.exists(images.path(old,false))).isFalse();assertThat(key()).isEqualTo(next);assertThat(files()).isEqualTo(2);
    }
    @Test void auditFailureRestoresOldImageAndRemovesBothNewFiles() throws Exception {
        String old=avatars.replace(actor,png());long version=Sql.id(one("SELECT version FROM users WHERE id=?",actor).get("version"));failAudit();
        assertThatThrownBy(()->avatars.replace(actor,png())).isInstanceOf(IllegalStateException.class);
        assertThat(key()).isEqualTo(old);assertThat(files()).isEqualTo(2);
        assertThat(Sql.id(one("SELECT version FROM users WHERE id=?",actor).get("version"))).isEqualTo(version);
        assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE actor_user_id=? AND event_type='AVATAR_UPDATED'",actor)).isEqualTo(1);
    }
    @Test void concurrentReplacementsLeaveOnlyCurrentPair() throws Exception {
        avatars.replace(actor,png());var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);byte[] image=png();
        try {
            var a=pool.submit(()->{gate.await();return avatars.replace(actor,image);});var b=pool.submit(()->{gate.await();return avatars.replace(actor,image);});gate.countDown();
            String first=a.get(30,TimeUnit.SECONDS),second=b.get(30,TimeUnit.SECONDS);
            assertThat(key()).isIn(first,second);assertThat(Files.exists(images.path(key(),false))).isTrue();assertThat(files()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE actor_user_id=? AND event_type='AVATAR_UPDATED'",actor)).isEqualTo(3);
        } finally {pool.shutdownNow();}
    }
    @Test void cleanupFailureAfterCommitDoesNotRemoveCommittedImage() throws Exception {
        String old=avatars.replace(actor,png());var spy=spy(images);doThrow(new IOException("disk busy")).when(spy).remove(old);
        String next=new AvatarService(source,spy).replace(actor,png());assertThat(key()).isEqualTo(next);assertThat(Files.exists(images.path(next,true))).isTrue();
    }
    @Test void revokedProfilePermissionFailsBeforeFileAllocation() {
        update("DELETE FROM user_roles WHERE user_id=?",actor);
        assertThatThrownBy(()->avatars.replace(actor,png())).isInstanceOf(SecurityException.class);
        assertThat(key()).isEmpty();assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE actor_user_id=?",actor)).isZero();
    }
}
