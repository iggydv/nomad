package org.nomad.pithos.components

import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Multimaps
import org.nomad.grpc.superpeerservice.MetaDataCollection
import org.nomad.pithos.mappers.CustomMappers
import org.nomad.pithos.models.MetaData
import java.time.Instant
import java.util.stream.Collectors

class Ledger {
    private val ledgerMultiMap: Multimap<String, MetaData> = Multimaps.synchronizedMultimap(HashMultimap.create())
    fun countReplicas(key: String?): Int {
        return ledgerMultiMap[key].size
    }

    /**
     * For a given replication factor, the method determines which of its keys requires repair
     *
     * @param rf the required replication factor
     * @return a list of keys
     */
    fun getObjectsNeedingRepair(rf: Int): ArrayList<String> {
        cleanExpiredObjects()
        synchronized(ledgerMultiMap) {
            return ArrayList(ledgerMultiMap.keySet()
                    .stream()
                    .filter { key: String -> countReplicas(key) < rf }
                    .collect(Collectors.toList()))
        }
    }

    operator fun get(key: String): ArrayList<MetaData> {
        return ArrayList(ledgerMultiMap[key])
    }

    fun add(key: String, value: MetaData?): Boolean {
        synchronized(ledgerMultiMap) { return ledgerMultiMap.put(key, value) }
    }

    fun remove(key: String, value: MetaData?) {
        synchronized(ledgerMultiMap) { ledgerMultiMap.remove(key, value) }
    }

    fun removeAll(key: String) {
        synchronized(ledgerMultiMap) { ledgerMultiMap.removeAll(key) }
    }

    fun containsKey(key: String): Boolean {
        return ledgerMultiMap.containsKey(key)
    }

    fun removeValue(value: MetaData) {
        synchronized(ledgerMultiMap) { ledgerMultiMap.values().removeIf { metaData: MetaData -> metaData == value } }
    }

    fun removeValueNoTTL(value: String) {
        synchronized(ledgerMultiMap) { ledgerMultiMap.values().removeIf { metaData: MetaData -> metaData.id == value } }
    }

    fun containsEntry(key: String, value: MetaData?): Boolean {
        return ledgerMultiMap.containsEntry(key, value)
    }

    fun containsEntry(key: String, value: String): Boolean {
        synchronized(ledgerMultiMap) { return ledgerMultiMap[key].stream().anyMatch { metaData: MetaData -> metaData.id == value } }
    }

    fun cleanExpiredObjects(): Boolean {
        if (ledgerMultiMap.isEmpty) {
            return false
        }
        val expiryInstant = Instant.now().epochSecond
        synchronized(ledgerMultiMap) { return ledgerMultiMap.values().removeIf { metadata: MetaData -> ttlIsExpired(metadata, expiryInstant) } }
    }

    private fun ttlIsExpired(metaData: MetaData, expiryInstant: Long): Boolean {
        return metaData.ttl <= expiryInstant
    }

    fun containsValue(value: MetaData?): Boolean {
        return ledgerMultiMap.containsValue(value)
    }

    fun containsEntryNoTTL(key: String, value: String): Boolean {
        synchronized(ledgerMultiMap) { return ledgerMultiMap[key].stream().anyMatch { metaData: MetaData -> metaData.id == value } }
    }

    fun keySet(): Set<String> {
        return HashSet(ledgerMultiMap.keySet())
    }

    fun values(): Collection<MetaData> {
        return ArrayList(ledgerMultiMap.values())
    }

    fun asMap(): HashMap<String, Collection<MetaData>> {
        return HashMap(ledgerMultiMap.asMap())
    }

    /**
     * Should only be used for the gRPC join-response
     *
     * @return converted map to be sent to the joining peer
     */
    fun asGrpcMetaDataCollection(): HashMap<String, MetaDataCollection> {
        return HashMap(CustomMappers.INSTANCE.convertToGrpcMap(ledgerMultiMap.asMap()))
    }

    fun populateLedger(map: HashMap<String, Collection<MetaData>>) {
        for ((key, value) in map) {
            ledgerMultiMap.putAll(key, value)
        }
    }

    fun clear() {
        ledgerMultiMap.clear()
    }
}