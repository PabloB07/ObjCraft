package com.objcraft.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FileModelManagerTextureTest {
    @Test
    void downsamplesLargeTexturesInsteadOfRejectingThem() throws Exception {
        final BufferedImage source = new BufferedImage(4_096, 2_048, BufferedImage.TYPE_BYTE_BINARY);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", encoded));

        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
            final var readers = ImageIO.getImageReaders(input);
            assertTrue(readers.hasNext());
            final var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                final BufferedImage decoded = FileModelManager.readTexture(reader, "large.png");

                assertEquals(2_048, decoded.getWidth());
                assertEquals(1_024, decoded.getHeight());
                assertTrue((long) decoded.getWidth() * decoded.getHeight() <= 4_194_304L);
            } finally {
                reader.dispose();
            }
        }
    }
}
