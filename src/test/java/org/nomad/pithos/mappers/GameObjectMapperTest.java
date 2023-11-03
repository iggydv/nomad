package org.nomad.pithos.mappers;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nomad.grpc.models.GameObjectGrpc;
import org.nomad.pithos.models.GameObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GameObjectMapperTest {

    final Instant time = Instant.now();
    final long nowAsUnixTime = time.getEpochSecond();

    final byte[] valueAsByteArray = "Hello there".getBytes(StandardCharsets.UTF_8);
    final ByteString valueAsByteString = ByteString.copyFrom(valueAsByteArray);

    final String id = "c98da755-418e-42a5-acce-f03653940397";

    GameObjectGrpc grpcGameObject = GameObjectGrpc.newBuilder()
            .setId(id)
            .setCreationTime(nowAsUnixTime)
            .setValue(valueAsByteString)
            .setTtl(600)
            .build();

    GameObject internalGameObject = GameObject.builder()
            .id(id)
            .creationTime(nowAsUnixTime)
            .value(valueAsByteArray)
            .ttl(600)
            .build();

    org.nomad.api.model.GameObject apiGameObject = new org.nomad.api.model.GameObject()
            .id(id)
            .creationTime(nowAsUnixTime)
            .value(valueAsByteArray)
            .ttl(600L);

    @Test
    @DisplayName("Should map from Internal Object to gRPC Object")
    void mapToGrpc() {
        GameObjectGrpc result = GameObjectMapperImpl.INSTANCE.mapToGrpc(internalGameObject);
        assertEquals(grpcGameObject, result);
        assertEquals(internalGameObject.getId(), result.getId());
        assertEquals(nowAsUnixTime, result.getCreationTime());
        assertEquals(grpcGameObject.getTtl(), result.getTtl());
        assertEquals(valueAsByteString, result.getValue());
    }

    @Test
    @DisplayName("Should map from gRPC Object to Internal Object")
    void mapToInternal() {
        GameObject result = GameObjectMapperImpl.INSTANCE.mapToInternal(grpcGameObject);
        assertEquals(internalGameObject, result);
        assertEquals(grpcGameObject.getId(), result.getId());
        assertEquals(nowAsUnixTime, result.getCreationTime());
        assertEquals(grpcGameObject.getTtl(), result.getTtl());
        assertArrayEquals(valueAsByteArray, result.getValue());
    }

    @Test
    @DisplayName("Should map from API Object to Internal Object")
    void mapToInternalFromAPIModel() {
        GameObject result = GameObjectMapperImpl.INSTANCE.mapToInternal(apiGameObject);
        assertEquals(internalGameObject, result);
        assertEquals(grpcGameObject.getId(), result.getId());
        assertEquals(nowAsUnixTime, result.getCreationTime());
        assertEquals(grpcGameObject.getTtl(), result.getTtl());
        assertArrayEquals(valueAsByteArray, result.getValue());
    }

    @Test
    @DisplayName("Should map from Internal Object to API Object")
    void mapToAPIModel() {
        org.nomad.api.model.GameObject result = GameObjectMapperImpl.INSTANCE.mapToAPI(internalGameObject);
//        issue with the .equals method - doesn't use arrays.equals
//        assert result.equals(apiGameObject);
        assertEquals(apiGameObject.getId(), result.getId());
        assertEquals(nowAsUnixTime, result.getCreationTime());
        assertEquals(apiGameObject.getTtl(), result.getTtl());
        assertArrayEquals(valueAsByteArray, result.getValue());
    }
}