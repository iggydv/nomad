package org.nomad.commons;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class Base64UtilityTest {
    final Base64Utility base64Utility = new Base64Utility();

    @Test
    void encodeImage() {
        byte[] image = base64Utility.encodeImage("pictures/agent-smith.jpg", "jpg");
        assert image != null;
    }

    @Test
    void byteStringToImage() {
        byte[] image = base64Utility.encodeImage("pictures/agent-smith.jpg", "jpg");
        assert base64Utility.byteStringToImage(image, "src/test/resources/pictures/output.jpg", "jpg");
    }

    @Test
    void encodeImageToString() {
        String s = base64Utility.encodeImageToString("pictures/agent-smith.jpg");
        Assertions.assertNotNull(s);
    }

    @Test
    void encodeRandomString() {
        String s = base64Utility.randomBase64String(1024);
        Assertions.assertNotNull(s);
    }
}