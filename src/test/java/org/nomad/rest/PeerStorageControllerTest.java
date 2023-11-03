package org.nomad.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.pithos.models.MetaData;
import org.nomad.storage.PeerStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("h2")
@ContextConfiguration(classes = {PeerStorageController.class, PeerStorage.class})
@WebMvcTest(PeerStorageController.class)
public class PeerStorageControllerTest {

    final long unixTime = Instant.now().getEpochSecond();
    private final GameObject testObject = GameObject.builder()
            .id("1")
            .value("Hello there!".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .lastModified(unixTime)
            .ttl(unixTime + 600L)
            .build();
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private PeerStorage peerStorage;

    @Test
    void getObject() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String json = ow.writeValueAsString(testObject);

        Mockito.when(peerStorage.get(Mockito.anyString(), Mockito.anyBoolean(), Mockito.anyBoolean())).thenReturn(testObject);
        Mockito.when(peerStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(get("/nomad/storage/get/{objectId}", "1")
                .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andExpect(content().json(json))
                .andReturn();
    }

    @Test
    void putObject() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        Mockito.when(peerStorage.put(Mockito.any(), Mockito.anyBoolean(), Mockito.anyBoolean())).thenReturn(true);
        Mockito.when(peerStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(post("/nomad/storage/put")
                .content(ow.writeValueAsString(testObject))
                .contentType(MediaType.APPLICATION_JSON)
                .contextPath("/nomad"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    void updateObject() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        Mockito.when(peerStorage.update(Mockito.any())).thenReturn(true);
        Mockito.when(peerStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(post("/nomad/storage/update")
                .content(ow.writeValueAsString(testObject))
                .contentType(MediaType.APPLICATION_JSON)
                .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void getObjectLedger() throws Exception {
        GroupLedger ledger = GroupLedger.getInstance();
        long ut = Instant.now().getEpochSecond();
        MetaData testMetaData3 = MetaData.builder().id("peer3").ttl(ut + 100L).build();
        ledger.addToObjectLedger("obj1", testMetaData3);

        Mockito.when(peerStorage.getGroupLedger()).thenReturn(ledger);
        Mockito.when(peerStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(get("/nomad/storage/get/object-ledger")
                .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void getPeerLedger() throws Exception {
        GroupLedger ledger = GroupLedger.getInstance();
        long ut = Instant.now().getEpochSecond();
        MetaData testMetaData3 = MetaData.builder().id("peer3").ttl(ut + 100L).build();
        ledger.addToObjectLedger("obj1", testMetaData3);

        Mockito.when(peerStorage.getGroupLedger()).thenReturn(ledger);
        Mockito.when(peerStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(get("/nomad/storage/get/peer-ledger")
                .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andReturn();
    }
}