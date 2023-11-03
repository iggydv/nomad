package org.nomad.pithos.mappers;

import com.google.protobuf.ByteString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.nomad.grpc.superpeerservice.MetaDataCollection;
import org.nomad.grpc.superpeerservice.MultiMapPair;
import org.nomad.pithos.models.MetaData;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

public class CustomMappers {
    public static CustomMappers INSTANCE = new CustomMappers();

    public byte[] byteStringToByteArray(ByteString bytes) {
        return bytes.toByteArray();
    }

    public byte[] copyByteArray(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public ByteString byteArrayToByteString(byte[] bytes) {
        return ByteString.copyFrom(bytes);
    }

    public Object2ObjectOpenHashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> convertToGrpcMap(Map<String, java.util.Collection<MetaData>> map) {
        Object2ObjectOpenHashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> newMap = new Object2ObjectOpenHashMap<>();
        map.forEach((key, value) -> newMap.put(key, org.nomad.grpc.superpeerservice.MetaDataCollection.newBuilder().addAllValues(convertToGrpcMetaData(value)).build()));

        return newMap;
    }

    public Object2ObjectOpenHashMap<String, ObjectCollection<MetaData>> convertToMap(MultiMapPair multiMapPair) {
        Map<String, MetaDataCollection> from = multiMapPair.getKeyPairMap();
        Object2ObjectOpenHashMap<String, ObjectCollection<MetaData>> to = new Object2ObjectOpenHashMap<>();
        from.forEach((key, value) -> {
            to.put(key, convertToMetaData(value.getValuesList()));
        });

        return to;
    }

    public ObjectCollection<org.nomad.grpc.superpeerservice.MetaData> convertToGrpcMetaData(Collection<MetaData> metaDataList) {
        ObjectCollection<org.nomad.grpc.superpeerservice.MetaData> resultList = new ObjectArrayList<>();
        metaDataList.forEach(metaData -> {
            resultList.add(org.nomad.grpc.superpeerservice.MetaData.newBuilder().setId(metaData.getId()).setTtl(metaData.getTtl()).build());
        });
        return resultList;
    }

    public ObjectCollection<MetaData> convertToMetaData(Collection<org.nomad.grpc.superpeerservice.MetaData> metaDataList) {
        ObjectCollection<MetaData> resultList = new ObjectArrayList<>();
        metaDataList.forEach(metaData -> {
            resultList.add(MetaData.builder().id(metaData.getId()).ttl(metaData.getTtl()).build());
        });
        return resultList;
    }
}