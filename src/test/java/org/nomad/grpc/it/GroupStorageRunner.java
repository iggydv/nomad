package org.nomad.grpc.it;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.nomad.commons.Base64Utility;
import org.nomad.config.Config;
import org.nomad.grpc.management.clients.GroupStorageClient;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.services.GroupStorageService;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.local.H2ObjectStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts up a server and some clients, does some key value store operations, and then measures
 * how many operations were completed.
 */
@Slf4j
@ActiveProfiles("dev,h2")
@EnableConfigurationProperties(value = Config.class)
@TestPropertySource("classpath:application.yml")
@ContextConfiguration(classes = {H2ObjectStorage.class, GroupLedger.class, Config.class, GroupStorageService.class})
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
public class GroupStorageRunner {

    private static final long DURATION_SECONDS = 30;
    @Autowired
    GroupStorageService groupStorageService;
    @Autowired
    H2ObjectStorage local;
    @Mock
    SuperPeerClient superPeerClient;
    long unixTime = Instant.now().getEpochSecond();
    private Server server;
    private ManagedChannel channel;

    @BeforeAll
    public void setup() throws SQLException, IOException, InterruptedException {
        Mockito.when(superPeerClient.addObjectReference(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
                .thenAnswer((Answer<Boolean>) invocation -> {
                    Thread.sleep(30);
                    return true;
                });

        local.init();
        local.initialiseTable();
        groupStorageService.updateSuperPeerClient(superPeerClient);
        groupStorageService.updateHostname("localhost:0");
        startServer();
    }

    @AfterAll
    public void cleanUp() throws InterruptedException {
        stopServer();
    }

    @Test
    @Disabled
    public void testFutureStub() {
        runClient();
    }

    @Test
    @Disabled
    public void testBlockingStub() {
        runBlockingClient();
    }

    private void runClient() {
        initializeChannel();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicBoolean done = new AtomicBoolean();
            GroupStorageClient client = new GroupStorageClient(channel);
            log.error("Starting the test ...");
            scheduler.schedule(() -> done.set(true), DURATION_SECONDS, TimeUnit.SECONDS);

            byte[] objectValue = new Base64Utility().encodeImage("pictures/agent-smith.jpg", "jpg");
            GameObject gameObject = GameObject.builder()
                    .id("111")
                    .value(objectValue)
                    .creationTime(unixTime)
                    .ttl(600L)
                    .build();

            client.doClientWork(done, gameObject);
            double qps = (double) client.getRpcCount() / DURATION_SECONDS;
            log.error("Did {} RPCs/s", qps);
        } catch (InterruptedException e) {
            // e.printStackTrace();
        } finally {
            scheduler.shutdownNow();
            channel.shutdownNow();
        }
    }

    private void runBlockingClient() {
        initializeChannel();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicBoolean done = new AtomicBoolean();
            GroupStorageClient client = new GroupStorageClient(channel);
            log.error("Starting the test ...");
            scheduler.schedule(() -> done.set(true), DURATION_SECONDS, TimeUnit.SECONDS);

            byte[] objectValue = new Base64Utility().encodeImage("pictures/agent-smith.jpg", "jpg");
            GameObject gameObject = GameObject.builder()
                    .id("111")
                    .value(objectValue)
                    .creationTime(unixTime)
                    .ttl(600L)
                    .build();

            client.doClientWork(done, gameObject);
            double qps = (double) client.getRpcCount() / DURATION_SECONDS;
            log.error("Did {} RPCs/s", qps);
        } catch (InterruptedException e) {
            // e.printStackTrace();
        } finally {
            scheduler.shutdownNow();
            channel.shutdownNow();
        }
    }

    private void initializeChannel() {
        if (channel != null) {
            throw new IllegalStateException("Already started");
        }

        channel = ManagedChannelBuilder.forTarget("localhost:" + server.getPort())
                .usePlaintext()
                .build();
    }

    private void startServer() throws IOException {
        if (server != null) {
            throw new IllegalStateException("Already started");
        }
        server = ServerBuilder.forPort(0).addService(groupStorageService).build();
        server.start();
    }

    private void stopServer() throws InterruptedException {
        Server s = server;
        if (s == null) {
            throw new IllegalStateException("Already stopped");
        }
        server = null;
        s.shutdown();
        if (s.awaitTermination(1, TimeUnit.SECONDS)) {
            return;
        }
        s.shutdownNow();
        if (s.awaitTermination(1, TimeUnit.SECONDS)) {
            return;
        }
        throw new RuntimeException("Unable to shutdown server");
    }
}