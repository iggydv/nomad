package org.nomad.commons;

import org.junit.jupiter.api.Test;

class KeyGeneratorTest {
    String uuidString = "d290f1ee-6c54-4b01-90e6-d701748f0851";
    String base64Uuid = "0pDx7mxUSwGQ5tcBdI8IUQ";

    @Test
    void uuidToBase64Test() {
        String result = KeyGenerator.uuidToBase64(uuidString);
        assert result.equals(base64Uuid);
    }

    @Test
    void uuidFromBase64Test() {
        String result = KeyGenerator.uuidFromBase64(base64Uuid);
        assert result.equals(uuidString);
    }
}