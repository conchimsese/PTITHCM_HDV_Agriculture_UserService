package vn.agriculture.soa.user.media;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.agriculture.soa.user.error.ApiException;

class AvatarImagesTest {
    private final AvatarImages images = new AvatarImages();
    static byte[] image(String format) throws Exception {
        var pixels = new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0,0,0xff123456);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(pixels,format,bytes);
        return bytes.toByteArray();
    }
    @Test void acceptsActualPngDespiteUntrustedNameAndMimeAndDropsTrailingContent() throws Exception {
        byte[] original = image("png");
        var bytes = new ByteArrayOutputStream();
        bytes.write(original); bytes.write("<script>untrusted payload</script>".getBytes());
        var result = images.sanitize(new MockMultipartFile("file","../../anything.exe","application/octet-stream",bytes.toByteArray()));
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(new String(result.bytes(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("untrusted payload");
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes())).getWidth()).isEqualTo(3);
    }
    @Test void acceptsJpeg() throws Exception {
        var result = images.sanitize(new MockMultipartFile("file",image("jpeg")));
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes())).getHeight()).isEqualTo(2);
    }
    @Test void decodesActualWebpAndNormalizesToPng() throws Exception {
        try (var resource = getClass().getResourceAsStream("/media/small_1x1.webp")) {
            assertThat(resource).isNotNull();
            var result = images.sanitize(new MockMultipartFile("file","avatar.webp","image/webp",resource.readAllBytes()));
            assertThat(result.contentType()).isEqualTo("image/png");
            var pixels = ImageIO.read(new ByteArrayInputStream(result.bytes()));
            assertThat(pixels.getWidth()).isEqualTo(1);
            assertThat(pixels.getHeight()).isEqualTo(1);
        }
    }
    @Test void rejectsForgedMimeAndUnsupportedGif() throws Exception {
        for (byte[] data : new byte[][] { "<svg onload='bad'/>".getBytes(), image("gif"), new byte[] {1,2,3} }) {
            assertThatThrownBy(() -> images.sanitize(new MockMultipartFile("file","avatar.png","image/png",data)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(415));
        }
    }
    @Test void rejectsEmptyAndOverFiveMiB() {
        assertThatThrownBy(() -> images.sanitize(new MockMultipartFile("file",new byte[0])))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(400));
        assertThatThrownBy(() -> images.sanitize(new MockMultipartFile("file",new byte[AvatarImages.MAX_BYTES+1])))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(413));
    }
    @Test void rejectsOversizedDimensionsBeforeAllocatingPixels() throws Exception {
        byte[] png = image("png");
        ByteBuffer.wrap(png).putInt(16,5000).putInt(20,4001);
        CRC32 crc = new CRC32(); crc.update(png,12,17);
        ByteBuffer.wrap(png).putInt(29,(int) crc.getValue());
        assertThatThrownBy(() -> images.sanitize(new MockMultipartFile("file",png)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(413));
    }
    @Test void truncatedPngIsNotAccepted() throws Exception {
        byte[] png = java.util.Arrays.copyOf(image("png"),40);
        assertThatThrownBy(() -> images.sanitize(new MockMultipartFile("file",png)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(415));
    }
}
