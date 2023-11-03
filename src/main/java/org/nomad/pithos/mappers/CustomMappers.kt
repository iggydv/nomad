package org.nomad.pithos.mappers

import com.google.protobuf.ByteString
import org.nomad.grpc.superpeerservice.MetaDataCollection
import org.nomad.grpc.superpeerservice.MultiMapPair
import org.nomad.pithos.models.MetaData
import java.util.function.Consumer
import org.nomad.grpc.superpeerservice.MetaData as GrpcMetadata

class CustomMappers {
    fun byteStringToByteArray(bytes: ByteString): ByteArray {
        return bytes.toByteArray()
    }

    fun copyByteArray(bytes: ByteArray): ByteArray {
        return bytes.copyOf(bytes.size)
    }

    fun byteArrayToByteString(bytes: ByteArray?): ByteString {
        return ByteString.copyFrom(bytes)
    }

    fun convertToGrpcMap(map: Map<String, Collection<MetaData>>): HashMap<String, MetaDataCollection> {
        val newMap = HashMap<String, MetaDataCollection>()
        map.forEach { (key: String, value: Collection<MetaData>) -> newMap[key] = MetaDataCollection.newBuilder().addAllValues(convertToGrpcMetaData(value)).build() }
        return newMap
    }

    fun convertToMap(multiMapPair: MultiMapPair): HashMap<String, List<MetaData>> {
        val from = multiMapPair.keyPairMap
        val to = HashMap<String, List<MetaData>>()
        from.forEach { (key: String, value: MetaDataCollection) -> to[key] = convertToMetaData(value.valuesList) }
        return to
    }

    fun convertToGrpcMetaData(metaDataList: Collection<MetaData>): ArrayList<GrpcMetadata> {
        val resultList: ArrayList<GrpcMetadata> = ArrayList()
        metaDataList.forEach(Consumer { metaData: MetaData -> resultList.add(GrpcMetadata.newBuilder().setId(metaData.id).setTtl(metaData.ttl).build()) })
        return resultList
    }

    fun convertToMetaData(metaDataList: Collection<GrpcMetadata>): ArrayList<MetaData> {
        val resultList: ArrayList<MetaData> = ArrayList()
        metaDataList.forEach(Consumer { metaData: GrpcMetadata -> resultList.add(MetaData(metaData.id, metaData.ttl))})
        return resultList
    }

    companion object {
        @JvmField
        var INSTANCE = CustomMappers()
    }
}