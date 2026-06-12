package core.global.entity.image.service.impl;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class BenchmarkJpegFactory {

    private BenchmarkJpegFactory() {
    }

    static byte[] createEightMiBJpeg() {
        BufferedImage image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(32, 96, 160));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.WHITE);
            graphics.drawString("Kori NCP Object Storage Benchmark", 80, 120);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", output);
            return Arrays.copyOf(output.toByteArray(), 8 * 1024 * 1024);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create benchmark JPEG", e);
        }
    }
}
