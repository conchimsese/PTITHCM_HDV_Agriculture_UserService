package vn.agriculture.soa.user.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.agriculture.soa.user.error.ApiException;

/** Decode only accepted formats, then encode pixels without the uploaded metadata. */
@Component
public class AvatarImages {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final long MAX_PIXELS = 20_000_000L;
    public record Image(byte[] bytes, String contentType) { }

    public Image sanitize(MultipartFile file) {
        if (file == null || file.isEmpty()) { throw invalid("An image file is required"); }
        if (file.getSize() > MAX_BYTES) { throw tooLarge(); }
        try (var stream = file.getInputStream()) {
            byte[] input = stream.readNBytes(MAX_BYTES + 1);
            if (input.length > MAX_BYTES) { throw tooLarge(); }
            try (var images = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                var readers = ImageIO.getImageReaders(images);
                if (!readers.hasNext()) { throw unsupported(); }
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("jpeg", "jpg", "png", "webp").contains(format)) { throw unsupported(); }
                    reader.setInput(images, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) { throw tooLarge(); }
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) { throw unsupported(); }
                    // Normalize WebP to PNG; preserve alpha without native codec dependencies.
                    String outputFormat = format.equals("jpeg") || format.equals("jpg") ? "jpeg" : "png";
                    BufferedImage pixels = new BufferedImage(width, height,
                            outputFormat.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
                    var graphics = pixels.createGraphics();
                    try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); decoded.flush(); }
                    var output = new ByteArrayOutputStream();
                    try {
                        if (!ImageIO.write(pixels, outputFormat, output)) { throw unsupported(); }
                    } finally { pixels.flush(); }
                    byte[] bytes = output.toByteArray();
                    if (bytes.length > MAX_BYTES) { throw tooLarge(); }
                    return new Image(bytes, "image/" + outputFormat);
                } finally { reader.dispose(); }
            }
        } catch (ApiException ex) { throw ex; }
        catch (IOException | RuntimeException ex) { throw unsupported(); }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Invalid JPEG, PNG or WebP image");
    }
    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Image exceeds 5 MiB or 20 megapixels");
    }
}
