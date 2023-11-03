package org.nomad.pithos.mappers;

import com.google.protobuf.ByteString;
import it.unimi.dsi.fastutil.objects.HashMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.junit.jupiter.api.Test;
import org.nomad.grpc.superpeerservice.MultiMapPair;
import org.nomad.pithos.models.MetaData;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomMappersTest {

    final CustomMappers testMapper = new CustomMappers();
    final Instant time = Instant.now();
    final long unixTime = time.getEpochSecond();

    @Test
    void testByteStringToByteArrayMapper() {
        ByteString byteString = ByteString.copyFrom("test".getBytes(StandardCharsets.UTF_8));
        byte[] byteArray = testMapper.byteStringToByteArray(byteString);
        assertArrayEquals("test".getBytes(StandardCharsets.UTF_8), byteArray);
    }

    @Test
    void TestMapConversion() {
        Map<String, java.util.Collection<MetaData>> map = new HashMap<>();
        Map<String, org.nomad.grpc.superpeerservice.MetaDataCollection> grpcMap = new HashMap<>();

        List<MetaData> iterable = new ArrayList<>();
        iterable.add(MetaData.builder().id("value1").ttl(unixTime).build());
        iterable.add(MetaData.builder().id("value1").ttl(unixTime).build());

        List<org.nomad.grpc.superpeerservice.MetaData> iterableGrpc = new ArrayList<>();
        iterableGrpc.add(org.nomad.grpc.superpeerservice.MetaData.newBuilder().setId("value1").setTtl(unixTime).build());
        iterableGrpc.add(org.nomad.grpc.superpeerservice.MetaData.newBuilder().setId("value1").setTtl(unixTime).build());

        map.put("key", iterable);
        grpcMap.put("key", org.nomad.grpc.superpeerservice.MetaDataCollection.newBuilder().addAllValues(iterableGrpc).build());

        MultiMapPair m1 = MultiMapPair.newBuilder().putAllKeyPair(grpcMap).build();
        HashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> actualGRPCMap = testMapper.convertToGrpcMap(map);
        HashMap<String, ObjectCollection<MetaData>> actualMap = testMapper.convertToMap(m1);

        assertEquals(grpcMap, actualGRPCMap);
        assertEquals(map, actualMap);
    }
}