package org.nomad.pithos.components;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.nomad.pithos.mappers.CustomMappers;
import org.nomad.pithos.models.MetaData;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public class Ledger {
    private final Multimap<String, MetaData> ledgerMultiMap = Multimaps.synchronizedMultimap(HashMultimap.create());

    public int countReplicas(String key) {
        return ledgerMultiMap.get(key).size();
    }

    /**
     * For a given replication factor, the method determines which of it's keys requires repair
     *
     * @param rf the required replication factor
     * @return a list of keys
     */
    public ObjectList<String> getObjectsNeedingRepair(int rf) {
        cleanExpiredObjects();
        synchronized (ledgerMultiMap) {
            return new ObjectArrayList<>(ledgerMultiMap.keySet()
                    .stream()
                    .filter(key -> countReplicas(key) < rf)
                    .collect(Collectors.toList()));
        }
    }

    public ObjectCollection<MetaData> get(String key) {
        return new ObjectArrayList<>(ledgerMultiMap.get(key));
    }

    public boolean add(String key, MetaData value) {
        synchronized (ledgerMultiMap) {
            return ledgerMultiMap.put(key, value);
        }
    }

    public void remove(String key, MetaData value) {
        synchronized (ledgerMultiMap) {
            ledgerMultiMap.remove(key, value);
        }
    }

    public void removeAll(String key) {
        synchronized (ledgerMultiMap) {
            ledgerMultiMap.removeAll(key);
        }
    }

    public boolean containsKey(String key) {
        return ledgerMultiMap.containsKey(key);
    }

    public void removeValue(MetaData value) {
        synchronized (ledgerMultiMap) {
            ledgerMultiMap.values().removeIf(metaData -> metaData.equals(value));
        }
    }

    public void removeValueNoTTL(String value) {
        synchronized (ledgerMultiMap) {
            ledgerMultiMap.values().removeIf(metaData -> metaData.getId().equals(value));
        }
    }

    public boolean containsEntry(String key, MetaData value) {
        return ledgerMultiMap.containsEntry(key, value);
    }

    public boolean containsEntry(String key, String value) {
        synchronized (ledgerMultiMap) {
            return ledgerMultiMap.get(key).stream().anyMatch(metaData -> metaData.getId().equals(value));
        }
    }

    public boolean cleanExpiredObjects() {
        if (ledgerMultiMap.isEmpty()) {
            return false;
        }

        long expiryInstant = Instant.now().getEpochSecond();
        synchronized (ledgerMultiMap) {
            return ledgerMultiMap.values().removeIf(metadata -> ttlIsExpired(metadata, expiryInstant));
        }
    }

    private boolean ttlIsExpired(MetaData metaData, long expiryInstant) {
        return metaData.getTtl() <= expiryInstant;
    }

    /**
     * Returns the first key that stores this value
     *
     * @param value of the object we want
     */
    public String getFirstMatch(MetaData value) {
        synchronized (ledgerMultiMap) {
            for (String key : ledgerMultiMap.keySet()) {
                if (ledgerMultiMap.get(key).stream().anyMatch(x -> x.equals(value))) {
                    return key;
                }
            }
        }
        return null;
    }

    public boolean containsValue(MetaData value) {
        return ledgerMultiMap.containsValue(value);
    }

    public boolean containsEntryNoTTL(String key, String value) {
        synchronized (ledgerMultiMap) {
            return ledgerMultiMap.get(key).stream().anyMatch(metaData -> metaData.getId().equals(value));
        }
    }

    public ObjectOpenHashSet<String> keySet() {
        return new ObjectOpenHashSet<>(ledgerMultiMap.keySet());
    }

    public ObjectCollection<MetaData> values() {
        return new ObjectArrayList<>(ledgerMultiMap.values());
    }

    public Multimap<String, MetaData> getLedgerMultiMap() {
        return ledgerMultiMap;
    }

    public Object2ObjectOpenHashMap<String, Collection<MetaData>> asMap() {
        return new Object2ObjectOpenHashMap<>(ledgerMultiMap.asMap());
    }

    /**
     * Should only be used for the gRPC join-response
     *
     * @return converted map to be sent to the joining peer
     */
    public Object2ObjectOpenHashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> asGrpcMetaDataCollection() {
        return new Object2ObjectOpenHashMap<>(CustomMappers.INSTANCE.convertToGrpcMap(ledgerMultiMap.asMap()));
    }

    public void populateLedger(Object2ObjectOpenHashMap<String, ObjectCollection<MetaData>> map) {
        for (Map.Entry<String, ObjectCollection<MetaData>> entry : map.entrySet()) {
            ledgerMultiMap.putAll(entry.getKey(), entry.getValue());
        }
    }

    public void clear() {
        ledgerMultiMap.clear();
    }
}
