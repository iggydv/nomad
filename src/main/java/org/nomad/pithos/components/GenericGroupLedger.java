package org.nomad.pithos.components;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.nomad.grpc.superpeerservice.MultiMapPair;
import org.nomad.pithos.models.MetaData;

import java.util.Collection;

public interface GenericGroupLedger {

    /* Repair Data
    ================================================================================================================*/
    int countReplicas(String objectId);

    Object2ObjectOpenHashMap<String, Integer> objectsThatNeedRepair(int rf);

    ObjectList<String> removePeersStoringObject(Collection<String> peers, String objectId);

    ObjectList<String> removePeersNotStoringObject(Collection<String> peers, String objectId);


    /* Adding Data
    ================================================================================================================*/

    void populateGroupLedger(MultiMapPair objectLedger, MultiMapPair peerLedger);

    boolean addToObjectLedger(String objectId, MetaData peerId);

    boolean addToPeerLedger(String peerId, MetaData objectId);

    boolean objectLedgerContainsKey(String key);

    boolean peerLedgerContainsKey(String key);

    boolean objectLedgerContainsEntry(String key, MetaData value);

    boolean peerLedgerContainsEntry(String key, MetaData value);

    boolean thisPeerContainsObject(String key, String value);

    /* Removing Data
    ================================================================================================================*/

    void removeFromObjectLedger(String objectId, MetaData peerId);

    void removeFromPeerLedger(String peerId, MetaData objectId);

    void removePeerFromGroupLedger(String peerId);

    void removeObjectFromGroupLedger(String objectId);

    boolean cleanExpiredObjects();

    void clearAll();

    /* Viewing Data
    ================================================================================================================*/

    ObjectOpenHashSet<String> getAllGroupObjects();

    /**
     * Purely a method used for manual testing
     */
    void printObjectLedger();

    /**
     * Purely a method used for manual testing
     */
    void printPeerLedger();

    Object2ObjectOpenHashMap<String, Collection<MetaData>> getObjectLedgerAsMap();

    Object2ObjectOpenHashMap<String, Collection<MetaData>> getPeerLedgerAsMap();

    Object2ObjectOpenHashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> getGrpcObjectLedger();

    Object2ObjectOpenHashMap<String, org.nomad.grpc.superpeerservice.MetaDataCollection> getGrpcPeerLedger();

    /* Getters & Setters
    ================================================================================================================*/

    Ledger getObjectLedger();

    Ledger getPeerLedger();

    Ledger setObjectLedger(Object2ObjectOpenHashMap<String, Collection<String>> map);

    Ledger setPeerLedger();
}
