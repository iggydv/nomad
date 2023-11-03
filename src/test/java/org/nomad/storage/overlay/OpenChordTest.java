package org.nomad.storage.overlay;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.pithos.models.GameObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;

@ActiveProfiles("chord")
@ContextConfiguration(classes = {OpenChord.class, Config.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
class OpenChordTest {
    @Autowired
    private OpenChord chord;

    @Autowired
    private OpenChord chord2;

    GameObject testObject = GameObject.builder()
            .id("1a32425")
            .value("hello".getBytes(StandardCharsets.UTF_8))
            .build();

    @BeforeAll
    @SneakyThrows
    public void setupAll() {
        NetworkUtility.init();
        chord.initOverlay();
    }

    @AfterAll
    @SneakyThrows
    public void cleanUp() {
        chord.close();
    }

    @Test
    @SneakyThrows
    public void testPut() {
        Assertions.assertTrue(chord.put(testObject));
    }

    @Test
    @SneakyThrows
    @Disabled
    public void testJoinOverlay() {
        chord.put(testObject);
        chord2.joinOverlay("localhost:4001");
        GameObject result = chord2.get("1a32425");
        Assertions.assertEquals(testObject, result);
        chord2.close();
    }
}