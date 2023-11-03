package org.nomad.storage.overlay;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.pithos.models.GameObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.NoSuchElementException;

@ActiveProfiles("h2,tomp2p")
@ContextConfiguration(classes = {TomP2POverlayStorage.class, Config.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
class TomP2POverlayStorageTest {

    final long unixTime = Instant.now().getEpochSecond();
    private final GameObject testObject1 = buildTestObject("1");
    private final GameObject testObject2 = buildTestObject("2");

    @Autowired
    private TomP2POverlayStorage tomP2POverlayStorage;

    @Autowired
    private TomP2POverlayStorage tomP2POverlayStorage2;

    @BeforeAll
    public void setupAll() throws IOException, InterruptedException {
        NetworkUtility.init();
        tomP2POverlayStorage.initOverlay();
        tomP2POverlayStorage.put(buildTestObject("2"));
    }

    @AfterAll
    public void cleanUp() throws InterruptedException {
        tomP2POverlayStorage.close();
    }

    private GameObject buildTestObject(String id) {
        return GameObject.builder()
                .id(id)
                .value("test-bytes".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(unixTime + 600L)
                .build();
    }

    @Test
    public void testPut() throws IOException, ClassNotFoundException, InterruptedException {
        GameObject testObject = GameObject.builder()
                .id("1a32425")
                .value("hello".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(unixTime + 600L)
                .build();

        Assertions.assertTrue(tomP2POverlayStorage.put(testObject1));
        Assertions.assertEquals(testObject1, tomP2POverlayStorage.get("1"));

        Assertions.assertTrue(tomP2POverlayStorage.put(testObject));
        Assertions.assertEquals(testObject, tomP2POverlayStorage.get("1a32425"));

        Assertions.assertTrue(tomP2POverlayStorage.put(testObject1));
        Assertions.assertEquals(testObject1, tomP2POverlayStorage.get("1"));
    }

    @Test
    public void testGet() throws IOException, ClassNotFoundException, InterruptedException {
        GameObject testObject = buildTestObject("2");
        tomP2POverlayStorage.put(testObject);
        Assertions.assertEquals(testObject, tomP2POverlayStorage.get("2"));
        Assertions.assertThrows(NoSuchElementException.class, () -> tomP2POverlayStorage.get("-1"));
    }

    @Test
    public void testUpdate() throws IOException, ClassNotFoundException, InterruptedException {
        GameObject testObjectY = buildTestObject("y");
        tomP2POverlayStorage.put(testObjectY);
        Assertions.assertEquals(testObjectY, tomP2POverlayStorage.get("y"));

        testObjectY.setTtl(600);
        Assertions.assertTrue(tomP2POverlayStorage.update(testObjectY));
        Assertions.assertEquals(600, tomP2POverlayStorage.get("y").getTtl());
    }

    @Test
    public void testDelete() throws IOException, InterruptedException, ClassNotFoundException {
        GameObject testObjectX = buildTestObject("x");
        tomP2POverlayStorage.put(testObjectX);
        Assertions.assertEquals(testObjectX, tomP2POverlayStorage.get("x"));
        Assertions.assertTrue(tomP2POverlayStorage.delete("x"));
        Assertions.assertThrows(NoSuchElementException.class, () -> tomP2POverlayStorage.get("x"));
    }

    @Test
    public void testGetExpiredObject() throws IOException, InterruptedException, ClassNotFoundException {
        GameObject testObject = GameObject.builder()
                .id("99")
                .value("hello".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(unixTime + 2L)
                .build();

        tomP2POverlayStorage.put(testObject);
        Assertions.assertEquals(testObject, tomP2POverlayStorage.get("99"));
        Thread.sleep(2000);
        Assertions.assertThrows(NoSuchElementException.class, () -> tomP2POverlayStorage.get("99"));
    }

    @Test
    public void testJoinOverlay() throws IOException, InterruptedException, ClassNotFoundException {
        int id = tomP2POverlayStorage2.joinOverlay("localhost:" + tomP2POverlayStorage.getPeerDHTPort());
        GameObject testObject = buildTestObject("abc");
        tomP2POverlayStorage.put(testObject);
        GameObject getResult1 = tomP2POverlayStorage.get("abc");
        Assertions.assertEquals(testObject, getResult1);
        GameObject getResult2 = tomP2POverlayStorage2.get("abc");
        Assertions.assertEquals(testObject, getResult2);
    }
}