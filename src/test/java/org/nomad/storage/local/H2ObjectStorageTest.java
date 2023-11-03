package org.nomad.storage.local;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nomad.commons.Base64Utility;
import org.nomad.config.Config;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.NoSuchElementException;

import static org.junit.Assert.assertEquals;

@ActiveProfiles("dev,h2")
@EnableConfigurationProperties(value = Config.class)
@TestPropertySource("classpath:application.yml")
@ContextConfiguration(classes = {H2ObjectStorage.class, GroupLedger.class, Config.class})
@SpringBootTest
public class H2ObjectStorageTest {

    final long unixTime = Instant.now().getEpochSecond();
    @Autowired
    ApplicationContext appCtx;
    @Autowired
    Config config;
    @Autowired
    private H2ObjectStorage h2ObjectStorage;
    private GameObject testObject;

    @BeforeEach
    @SneakyThrows
    private void setup() {
        h2ObjectStorage.init();
        h2ObjectStorage.initialiseTable();
        testObject = buildTestObject("1");
        GameObject baseObject = buildTestObject("2");
        h2ObjectStorage.put(baseObject);
    }

    @AfterEach
    @SneakyThrows
    private void close() {
        h2ObjectStorage.truncate();
        h2ObjectStorage.close();
        GroupLedger.getInstance().clearAll();
    }

    @SneakyThrows
    private GameObject buildTestObject(String id) {
        byte[] objectValue = new Base64Utility().encodeImage("pictures/agent-smith.jpg", "jpg");
        return GameObject.builder()
                .id(id)
                .value(objectValue)
                .creationTime(unixTime)
                .ttl(600)
                .build();
    }

    @Test
    public void testPut() throws InterruptedException {
        Assertions.assertTrue(h2ObjectStorage.put(testObject));
        Assertions.assertFalse(h2ObjectStorage.put(testObject));
    }

    @Test
    public void testGet() {
        Assertions.assertNotNull(h2ObjectStorage.get("2"));
        Assertions.assertThrows(NoSuchElementException.class, () -> {
            h2ObjectStorage.get("2' OR '1' = '1");
        });
        Assertions.assertThrows(NoSuchElementException.class, () -> {
            h2ObjectStorage.get("-1");
        });
    }

    @Test
    @SneakyThrows
    public void testUpdate() {
        h2ObjectStorage.put(buildTestObject("2abc"));
        GameObject updated = buildTestObject("2abc");
        Assertions.assertTrue(h2ObjectStorage.update(updated));
        Assertions.assertEquals(unixTime, h2ObjectStorage.get("2abc").getCreationTime());
        // Update causes insert
        GameObject updated2 = buildTestObject("2abcd");
        Assertions.assertTrue(h2ObjectStorage.update(updated2));
    }

    @Test
    public void testDelete() throws SQLException {
        Assertions.assertTrue(h2ObjectStorage.delete("2"));
        Assertions.assertThrows(NoSuchElementException.class, () -> {
            h2ObjectStorage.get("2");
        });
        Assertions.assertThrows(NoSuchElementException.class, () -> {
            h2ObjectStorage.delete("2");
        });
    }

    @Test
    public void testIsInitialised() {
        Assertions.assertTrue(h2ObjectStorage.isInitialised());
        h2ObjectStorage.close();
        Assertions.assertFalse(h2ObjectStorage.isInitialised());
    }

    @Test
    public void testCleanUp() {
        // ttl is stored in the DB as creationTime + ttl
        long a = unixTime + 200L;
        long b = unixTime - 200L;

        GameObject g1 = GameObject.builder()
                .id("5")
                .value("objectValue".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(a)
                .build();

        GameObject g2 = GameObject.builder()
                .id("6")
                .value("objectValue".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(b)
                .build();

        GameObject g3 = GameObject.builder()
                .id("7")
                .value("objectValue".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(b)
                .build();

        GameObject g4 = GameObject.builder()
                .id("8")
                .value("objectValue".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(b)
                .build();

        h2ObjectStorage.put(g1);
        h2ObjectStorage.put(g2);
        h2ObjectStorage.put(g3);
        h2ObjectStorage.put(g4);

        System.out.println("5 expires at " + Instant.ofEpochSecond(a));
        System.out.println("6 expires at " + Instant.ofEpochSecond(b));

        h2ObjectStorage.cleanUp();

        Assertions.assertEquals(g1, h2ObjectStorage.get("5"));

        Assertions.assertThrows(NoSuchElementException.class, () -> {
            h2ObjectStorage.get("6");
        });
    }

    @Test
    public void whenConfiguredExcryptorUsed_ReturnCustomEncryptor() {
        Environment environment = appCtx.getBean(Environment.class);

        assertEquals(
                "WhiteRabbit",
                environment.getProperty("spring.datasource.password"));
    }

    @Test
    public void testTruncate() {
        Assertions.assertNotNull(h2ObjectStorage.get("2"));
        h2ObjectStorage.truncate();
        Assertions.assertThrows(NoSuchElementException.class, () -> {
            h2ObjectStorage.get("2");
        });
    }

}
