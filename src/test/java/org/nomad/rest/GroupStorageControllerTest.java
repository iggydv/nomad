package org.nomad.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nomad.config.Config;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.group.GroupStorage;
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
@ContextConfiguration(classes = {GroupStorageController.class, GroupStorage.class, Config.class})
@WebMvcTest(GroupStorageController.class)
class GroupStorageControllerTest {

    final long unixTime = Instant.now().getEpochSecond();
    private final GameObject testObject1 = GameObject.builder()
            .id("2")
            .value("Hello there!".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .ttl(unixTime + 600L)
            .build();

    private final GameObject testObject2 = GameObject.builder()
            .id("1")
            .value("Hello there!".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .ttl(unixTime + 600L)
            .build();

    private final GameObject testObject3 = GameObject.builder()
            .id("3")
            .value("Hello there!".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .ttl(unixTime + 600L)
            .build();
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private GroupStorage groupStorage;

    @Test
    void getFastObject() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String json = ow.writeValueAsString(testObject1);

        Mockito.when(groupStorage.safeGet(Mockito.anyString())).thenReturn(testObject1);
        Mockito.when(groupStorage.fastGet(Mockito.anyString())).thenReturn(testObject1);
        Mockito.when(groupStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(get("/nomad/group-storage/fast-get/{objectId}", "2")
                .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andExpect(content().json(json))
                .andReturn();
    }

    @Test
    void getSafeObject() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String json = ow.writeValueAsString(testObject1);

        Mockito.when(groupStorage.safeGet(Mockito.anyString())).thenReturn(testObject1);
        Mockito.when(groupStorage.fastGet(Mockito.anyString())).thenReturn(testObject1);
        Mockito.when(groupStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(get("/nomad/group-storage/safe-get/{objectId}", "2")
                .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andExpect(content().json(json))
                .andReturn();
    }

    @Test
    void putObjectFast() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();

        Mockito.when(groupStorage.fastPut(Mockito.any())).thenReturn(true);
        Mockito.when(groupStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(
                post("/nomad/group-storage/fast-put")
                        .content(ow.writeValueAsString(testObject2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void putObjectSafe() throws Exception {
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        Mockito.when(groupStorage.safePut(Mockito.any())).thenReturn(true);
        Mockito.when(groupStorage.isInitialized()).thenReturn(true);

        mockMvc.perform(
                post("/nomad/group-storage/safe-put")
                        .content(ow.writeValueAsString(testObject3))
                        .contentType(MediaType.APPLICATION_JSON)
                        .contextPath("/nomad"))
                .andExpect(status().isOk())
                .andReturn();
    }
}