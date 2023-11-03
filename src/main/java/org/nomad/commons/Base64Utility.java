package org.nomad.commons;

import com.google.protobuf.ByteString;
import org.apache.commons.io.FileUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class Base64Utility {

    public static byte[] encodeImage(String pathToFile, String format) {
        BufferedImage bufferedImage;
        try {
            bufferedImage = ImageIO.read(new File(pathToFile));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (ImageIO.write(bufferedImage, format, bos)) {
                return bos.toByteArray();
            }
        } catch (IOException e) {
             e.printStackTrace();
        }
        return "".getBytes(StandardCharsets.UTF_8);
    }

    public static String encodeImageToString(String pathToFile) {
        byte[] fileContent;
        try {
            fileContent = FileUtils.readFileToByteArray(new File(pathToFile));
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
        return Base64.getEncoder().encodeToString(fileContent);
    }

    public static boolean byteStringToImage(byte[] data, String outputPath, String format) {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
        BufferedImage bufferedImage;
        try {
            bufferedImage = ImageIO.read(byteArrayInputStream);
            return ImageIO.write(bufferedImage, format, new File(outputPath));
        } catch (IOException e) {
             e.printStackTrace();
        }
        return false;
    }

    public static byte[] randomBase64(int length) {
        Random random = ThreadLocalRandom.current();
        byte[] randomBytes = new byte[length];
        random.nextBytes(randomBytes);
        return randomBytes;
    }

    public static String randomBase64String(int length) {
        byte[] randomBytes = randomBase64(length);
        return Base64.getEncoder().encodeToString(randomBytes);
    }

    public static ByteString randomBase64ByteString(int length) {
        return ByteString.copyFrom(randomBase64(length));
    }
}
