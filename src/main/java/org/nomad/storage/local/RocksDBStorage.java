package org.nomad.storage.local;

import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.pithos.components.GenericGroupLedger;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.pithos.models.MetaData;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.TtlDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.NoSuchElementException;

@Component
@Profile("rocksdb")
@DependsOn("ZookeeperDirectoryServerClient")
public class RocksDBStorage implements LocalStorage {
    private final Config config;
    private final GenericGroupLedger groupLedger;
    private TtlDB db;
    private String peerId;
    private boolean initialised = false;
    private static final Logger logger = LoggerFactory.getLogger(RocksDBStorage.class);

    @Autowired
    public RocksDBStorage(Config configuration) {
        this.config = configuration;
        this.groupLedger = GroupLedger.getInstance();
    }

    @Override
    public void init() throws SQLException {
        logger.info("Opening RocksDB instance ...");
        TtlDB.loadLibrary();
        String id = NetworkUtility.getID();
        this.initialised = true;
        peerId = config.getNetworkHostnames().getGroupStorageServer(); // right now we use the hostname
        Options options = new Options().setCreateIfMissing(true).optimizeForSmallDb();
        try {
            String dirName = "nomad-rocksdb";
            String dbName = "/nomad-" + id;
            createDirectory(dirName);
            db = TtlDB.open(options, dirName + dbName, 300, false);
        } catch (RocksDBException | IOException e) {
            e.printStackTrace();
        }
    }

    private File createDirectory(String directoryPath) throws IOException {
        File dir = new File(directoryPath);
        if (dir.exists()) {
            return dir;
        }
        if (dir.mkdirs()) {
            return dir;
        }
        throw new IOException("Failed to create directory '" + dir.getAbsolutePath() + "' for an unknown reason.");
    }

    @Override
    public void close() throws SQLException {
        db.close();
        this.initialised = false;
    }

    @Override
    public boolean put(GameObject object) {
        String objectId = object.getId();
        long TTL = object.getTtl();
        logger.info("Adding object: {}", objectId);

        byte[] id = objectId.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] data = SerializationUtils.serialize(object);
            db.put(id, data);
            addToGroupLedger(objectId, TTL);
            return true;
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public GameObject get(String key) throws NoSuchElementException {
        logger.info("Retrieving object: {}", key);
        byte[] id = key.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] getResult = db.get(id);
            if (getResult == null) {
                throw new NoSuchElementException(key);
            }
            return (GameObject) SerializationUtils.deserialize(getResult);
        } catch (RocksDBException e) {
            e.printStackTrace();
            logger.error("Get failed: {}", e.getMessage());
        }
        throw new NoSuchElementException();
    }

    @Override
    public boolean update(GameObject object) throws NoSuchElementException {
        String objectId = object.getId();
        boolean isPut = !groupLedger.objectLedgerContainsKey(objectId);
        if (isPut) {
            return put(object);
        }

        byte[] id = object.getId().getBytes(StandardCharsets.UTF_8);
        try {
            logger.debug("Updating object: {}", objectId);
            byte[] data = SerializationUtils.serialize(object);
            db.put(id, data);
            return true;
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean delete(String key) throws NoSuchElementException {
        logger.debug("Deleting object: {}", key);
        byte[] id = key.getBytes(StandardCharsets.UTF_8);
        try {
            db.delete(id);
            return true;
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean isInitialised() {
        return false;
    }

    @Override
    public boolean isMalicious() {
        return false;
    }

    @Override
    public void truncate() {
    }

    private void addToGroupLedger(String objectId, long TTL) {
        if (peerId.isEmpty()) {
            logger.error("PeerID/group-storage hostname was not set correctly in the config!");
            peerId = config.getNetworkHostnames().getGroupStorageServer();
        }
//        logger.debug("Adding to Group Ledger (Object Ledger)- K: {}, V: {}", objectId, peerId);
        groupLedger.addToObjectLedger(objectId, MetaData.builder().ttl(TTL).id(peerId).build());
//        groupLedger.printObjectLedger();
//        logger.debug("Adding to Group Ledger (Peer Ledger)- K: {}, V: {}", peerId, objectId);
        groupLedger.addToPeerLedger(peerId, MetaData.builder().ttl(TTL).id(objectId).build());
//        groupLedger.printPeerLedger();
    }

}
