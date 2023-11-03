package org.nomad.pithos.components

import org.nomad.pithos.mappers.CustomMappers.convertToMap
import org.nomad.pithos.components.GenericGroupLedger
import org.nomad.pithos.components.Ledger
import java.util.stream.Collectors
import org.nomad.grpc.superpeerservice.MultiMapPair
import org.nomad.pithos.mappers.CustomMappers
import org.nomad.grpc.superpeerservice.MetaDataCollection
import org.nomad.pithos.components.GroupLedger
import org.nomad.pithos.models.MetaData

class GroupLedger : GenericGroupLedger {
    /* Getters & Setters
    ================================================================================================================*/  override val objectLedger = Ledger()
    override val peerLedger = Ledger()

    /* Repair Data
      ================================================================================================================*/
    override fun countReplicas(objectId: String?): Int {
        return objectLedger.countReplicas(objectId)
    }

    override fun objectsThatNeedRepair(rf: Int): HashMap<String, Int> {
        cleanExpiredObjects()
        val objectRepairMap: HashMap<String, Int> = HashMap()
        val objectsNeedingRepair: ArrayList<String> = objectLedger.getObjectsNeedingRepair(rf)
        objectsNeedingRepair.forEach { key ->
            val count = countReplicas(key)
            if (count < rf) {
                objectRepairMap.put(key, rf - count)
            }
        }
        return objectRepairMap
    }

    override fun removePeersStoringObject(peers: Collection<String?>?, objectId: String?): ArrayList<String?>? {
        return ArrayList(peers!!.stream().filter { peerId: String? -> !peerLedger.containsEntryNoTTL(peerId, objectId) }.collect(Collectors.toList<Any?>()))
    }

    override fun removePeersNotStoringObject(peers: Collection<String?>?, objectId: String?): ArrayList<String?>? {
        return ArrayList(peers!!.stream().filter { peerId: String? -> peerLedger.containsEntryNoTTL(peerId, objectId) }.collect(Collectors.toList<Any?>()))
    }

    /* Adding Data
    ================================================================================================================*/
    override fun populateGroupLedger(objectLedger: MultiMapPair?, peerLedger: MultiMapPair?) {
        this.objectLedger.populateLedger(CustomMappers.INSTANCE.convertToMap(objectLedger!!))
        this.peerLedger.populateLedger(CustomMappers.INSTANCE.convertToMap(peerLedger!!))
    }

    override fun addToObjectLedger(objectId: String?, peerId: MetaData?): Boolean {
        return objectLedger.add(objectId, peerId)
    }

    override fun addToPeerLedger(peerId: String?, objectId: MetaData?): Boolean {
        return peerLedger.add(peerId, objectId)
    }

    override fun objectLedgerContainsKey(key: String?): Boolean {
        return objectLedger.containsKey(key)
    }

    override fun peerLedgerContainsKey(key: String?): Boolean {
        return peerLedger.containsKey(key)
    }

    override fun objectLedgerContainsEntry(key: String?, value: MetaData?): Boolean {
        return objectLedger.containsEntry(key, value)
    }

    override fun peerLedgerContainsEntry(key: String?, value: MetaData?): Boolean {
        return peerLedger.containsEntry(key, value)
    }

    override fun thisPeerContainsObject(key: String?, value: String?): Boolean {
        return peerLedger.containsEntry(key, value)
    }

    /* Removing Data
    ================================================================================================================*/
    override fun removeFromObjectLedger(objectId: String?, peerId: MetaData?) {
        objectLedger.remove(objectId, peerId)
    }

    override fun removeFromPeerLedger(peerId: String?, objectId: MetaData?) {
        peerLedger.remove(peerId, objectId)
    }

    override fun removePeerFromGroupLedger(peerId: String?) {
        peerLedger.removeAll(peerId)
        objectLedger.removeValueNoTTL(peerId)
    }

    override fun removeObjectFromGroupLedger(objectId: String?) {
        objectLedger.removeAll(objectId)
        peerLedger.removeValueNoTTL(objectId)
    }

    override fun cleanExpiredObjects(): Boolean {
        val peerLedgerCleaned = peerLedger.cleanExpiredObjects()
        val objectLedgerCleaned = objectLedger.cleanExpiredObjects()
        return peerLedgerCleaned && objectLedgerCleaned
    }

    override fun clearAll() {
        peerLedger.clear()
        objectLedger.clear()
    }

    /* Viewing Data
    ================================================================================================================*/
    override val allGroupObjects: Set<String>
        get() = HashSet() (objectLedger.keySet())

    /**
     * Purely a method used for manual testing
     */
    override fun printObjectLedger() {
//        System.out.println("Object ledger:\n" + objectLedger.ledgerMultiMap.toString());
    }

    /**
     * Purely a method used for manual testing
     */
    override fun printPeerLedger() {
//        System.out.println("Peer ledger:\n" + peerLedger.ledgerMultiMap.toString());
    }

    override val objectLedgerAsMap: HashMap<String, Collection<MetaData>>
        get() = HashMap(objectLedger.asMap())
    override val peerLedgerAsMap: HashMap<String, Collection<MetaData>>
        get() = HashMap(peerLedger.asMap())
    override val grpcObjectLedger: HashMap<String, MetaDataCollection>
        get() = HashMap(objectLedger.asGrpcMetaDataCollection())
    override val grpcPeerLedger: HashMap<String, MetaDataCollection>
        get() = HashMap(peerLedger.asGrpcMetaDataCollection())

    override fun setObjectLedger(map: HashMap<String?, Collection<String?>?>?): Ledger {
        return objectLedger
    }

    override fun setPeerLedger(): Ledger? {
        return peerLedger
    }

    companion object {
        private var INSTANCE: GroupLedger? = null

        @JvmStatic
        @get:Synchronized
        val instance: GroupLedger?
            get() {
                if (INSTANCE == null) {
                    INSTANCE = GroupLedger()
                }
                return INSTANCE
            }
    }
}