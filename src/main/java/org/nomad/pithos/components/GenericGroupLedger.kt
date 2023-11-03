package org.nomad.pithos.components

import org.nomad.grpc.superpeerservice.MultiMapPair
import org.nomad.pithos.models.MetaData
import java.util.ArrayList

interface GenericGroupLedger {
    /* Repair Data
    ================================================================================================================*/
    fun countReplicas(objectId: String): Int
    fun objectsThatNeedRepair(rf: Int): HashMap<String, Int>?
    fun removePeersStoringObject(peers: Collection<String>, objectId: String): ArrayList<String>?
    fun removePeersNotStoringObject(peers: Collection<String>, objectId: String): ArrayList<String>?

    /* Adding Data
    ================================================================================================================*/
    fun populateGroupLedger(objectLedger: MultiMapPair?, peerLedger: MultiMapPair?)
    fun addToObjectLedger(objectId: String?, peerId: MetaData?): Boolean
    fun addToPeerLedger(peerId: String?, objectId: MetaData?): Boolean
    fun objectLedgerContainsKey(key: String?): Boolean
    fun peerLedgerContainsKey(key: String?): Boolean
    fun objectLedgerContainsEntry(key: String?, value: MetaData?): Boolean
    fun peerLedgerContainsEntry(key: String?, value: MetaData?): Boolean
    fun thisPeerContainsObject(key: String?, value: String?): Boolean

    /* Removing Data
    ================================================================================================================*/
    fun removeFromObjectLedger(objectId: String?, peerId: MetaData?)
    fun removeFromPeerLedger(peerId: String?, objectId: MetaData?)
    fun removePeerFromGroupLedger(peerId: String?)
    fun removeObjectFromGroupLedger(objectId: String?)
    fun cleanExpiredObjects(): Boolean
    fun clearAll()

    /* Viewing Data
    ================================================================================================================*/
    val allGroupObjects: HashSet<String?>?

    /**
     * Purely a method used for manual testing
     */
    fun printObjectLedger()

    /**
     * Purely a method used for manual testing
     */
    fun printPeerLedger()
    val objectLedgerAsMap: HashMap<String?, Collection<MetaData?>?>?
    val peerLedgerAsMap: HashMap<String?, Collection<MetaData?>?>?
    val grpcObjectLedger: HashMap<String?, Any?>?
    val grpcPeerLedger: HashMap<String?, Any?>?

    /* Getters & Setters
    ================================================================================================================*/
    val objectLedger: Ledger?
    val peerLedger: Ledger?
    fun setObjectLedger(map: HashMap<String?, Collection<String?>?>?): Ledger?
    fun setPeerLedger(): Ledger?
}