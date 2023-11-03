package org.nomad.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.overlay.DHTOverlayStorage;
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
@ContextConfiguration(classes = {OverlayStorageController.class, DHTOverlayStorage.class})
@WebMvcTest(OverlayStorageController.class)
class OverlayStorageControllerTest {

    final long unixTime = Instant.now().getEpochSecond();
    private final GameObject testObject = GameObject.builder()
            .id("2")
            .value("Hello there!".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .ttl(unixTime + 600L)
            .build();
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private DHTOverlayStorage overlayStorage;

    @Test
    void getOverlayObject() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String json = ow.writeValueAsString(testObject);

        Mockito.when(overlayStorage.get(Mockito.anyString())).thenReturn(testObject);
        mockMvc.perform(get("/nomad/overlay-storage/get/{objectId}", "2")
                .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andExpect(content().json(json))
                .andReturn();
    }

    @Test
    void putOverlayObject() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        Mockito.when(overlayStorage.put(Mockito.any())).thenReturn(true);

        mockMvc.perform(
                post("/nomad/overlay-storage/put")
                        .content(ow.writeValueAsString(testObject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andReturn();
    }
}