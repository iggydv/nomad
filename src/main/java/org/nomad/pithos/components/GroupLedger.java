package org.nomad.pithos.components;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.nomad.grpc.superpeerservice.MultiMapPair;
import org.nomad.pithos.mappers.CustomMappers;
import org.nomad.pithos.models.MetaData;

import java.util.Collection;
import java.util.stream.Collectors;

public class GroupLedger implements GenericGroupLedger {
    private static GroupLedger INSTANCE;
    private final Ledger objectLedger = new Ledger();
    private final Ledger peerLedger = new Ledger();

    public synchronized static GroupLedger getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GroupLedger();
        }
        return INSTANCE;
    }

    /* Repair Data
    ================================================================================================================*/
    @Override
    public int countReplicas(String objectId) {
        return objectLedger.countReplicas(objectId);
    }

    @Override
    public Object2ObjectOpenHashMap<String, Integer> objectsThatNeedRepair(int rf) {
        cleanExpiredObjects();
        Object2ObjectOpenHashMap<String, Integer> objectRepairMap = new Object2ObjectOpenHashMap<>();
        ObjectList<String> objectsNeedingRepair = objectLedger.getObjectsNeedingRepair(rf);
        objectsNeedingRepair.forEach(key -> {
            int count = countReplicas(key);
            if (count < rf) {
                objectRepairMap.put(key, (rf - count));
            }
        });
        return objectRepairMap;
    }

    @Override
    public ObjectList<String> removePeersStoringObject(Collection<String> peers, String objectId) {
        return new ObjectArrayList<>(peers.stream().filter(peerId -> !peerLedger.containsEntryNoTTL(peerId, objectId)).collect(Collectors.toList()));
    }

    @Override
    public ObjectList<String> removePeersNotStoringObject(Collection<String> peers, String objectId) {
        return new ObjectArrayList<>(peers.stream().filter(peerId -> peerLedger.containsEntryNoTTL(peerId, objectId)).collect(Collectors.toList()));
    }


    /* Adding Data
    ================================================================================================================*/

    @Override
    public void populateGroupLedger(MultiMapPair objectLedger, MultiMapPair peerLedger) {
        this.objectLedger.populateLedger(CustomMappers.INSTANCE.convertToMap(objectLedger));
        this.peerLedger.populateLedger(CustomMappers.INSTANCE.convertToMap(peerLedger));
    }

    @Override
    public boolean addToObjectLedger(String objectId, MetaData peerId) {
        return objectLedger.add(objectId, peerId);
    }

    @Override
    public boolean addToPeerLedger(String peerId, MetaData objectId) {
        return peerLedger.add(peerId, objectId);
    }

    @Override
    public boolean objectLedgerContainsKey(String key) {
        return objectLedger.containsKey(key);
    }

    @Override
    public boolean peerLedgerContainsKey(String key) {
        return peerLedger.containsKey(key);
    }

    @Override
    public boolean objectLedgerContainsEntry(String key, MetaData value) {
        return objectLedger.containsEntry(key, value);
    }

    @Override
    public boolean peerLedgerContainsEntry(String key, MetaData value) {
        return peerLedger.containsEntry(key, value);
    }

    @Override
    public boolean thisPeerContainsObject(String key, String value) {
        return peerLedger.containsEntry(key, value);
    }

    /* Removing Data
    ================================================================================================================*/

    @Override
    public void removeFromObjectLedger(String objectId, MetaData peerId) {
        objectLedger.remove(objectId, peerId);
    }

    @Override
    public void removeFromPeerLedger(String peerId, MetaData objectId) {
        peerLedger.remove(peerId, objectId);
    }

    @Override
    public void removePeerFromGroupLedger(String peerId) {
        peerLedger.removeAll(peerId);
        objectLedger.removeValueNoTTL(peerId);
    }

    @Override
    public void removeObjectFromGroupLedger(String objectId) {
        objectLedger.removeAll(objectId);
        peerLedger.removeValueNoTTL(objectId);
    }

    @Override
    public boolean cleanExpiredObjects() {
        boolean peerLedgerCleaned = peerLedger.cleanExpiredObjects();
        boolean objectLedgerCleaned = objectLedger.cleanExpiredObjects();
        return peerLedgerCleaned && objectLedgerCleaned;
    }

    @Override
    public void clearAll() {
        peerLedger.clear();
        objectLedger.clear();
    }

    /* Viewing Data
    ================================================================================================================*/

    @Override
    public ObjectOpenHashSet<String> getAllGroupObjects() {
        return new ObjectOpenHashSet<>(objectLedger.keySet());
    }

    /**
     * Purely a method used for manual testing
     */
    @Override
    public void printObjectLedger() {
//        System.out.println("Object ledger:\n" + objectLedger.ledgerMultiMap.toString());
    }

    /**
     * Purely a method used for manual testing
     */
    @Override
    public void printPeerLedger() {
//        System.out.println("Peer ledger:\n" + peerLedger.ledgerMultiMap.toString());
    }

    @Override
    public Object2ObjectOpenHashMap<String, Collection<MetaData>> getObjectLedgerAsMap() {
        return new Object2ObjectOpenHashMap<>(objectLedger.asMap());
    }

    @Override
    public Object2ObjectOpenHashMap<String, Collection<MetaData>> getPeerLedgerAsMap() {
        return new Object2ObjectOpenHashMap<>(peerLedger.asMap());
    }

    @Override
    public Object2ObjectOpenHashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> getGrpcObjectLedger() {
        return new Object2ObjectOpenHashMap<>(objectLedger.asGrpcMetaDataCollection());
    }

    @Override
    public Object2ObjectOpenHashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> getGrpcPeerLedger() {
        return new Object2ObjectOpenHashMap<>(peerLedger.asGrpcMetaDataCollection());
    }

    /* Getters & Setters
    ================================================================================================================*/

    @Override
    public Ledger getObjectLedger() {
        return objectLedger;
    }

    @Override
    public Ledger getPeerLedger() {
        return peerLedger;
    }

    @Override
    public Ledger setObjectLedger(Object2ObjectOpenHashMap<String, Collection<String>> map) {
        return objectLedger;
    }

    @Override
    public Ledger setPeerLedger() {
        return peerLedger;
    }
}
