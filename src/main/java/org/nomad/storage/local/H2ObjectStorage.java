package org.nomad.storage.local;

import com.ulisesbocchio.jasyptspringboot.annotation.EnableEncryptableProperties;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.nomad.commons.Base64Utility;
import org.nomad.config.Config;
import org.nomad.grpc.models.GameObjectGrpc;
import org.nomad.pithos.components.GenericGroupLedger;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.pithos.models.MetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;

@Component
@Profile("h2")
@EnableEncryptableProperties
public class H2ObjectStorage implements LocalStorage {
    private static final Logger logger = LoggerFactory.getLogger(H2ObjectStorage.class);
    private final GenericGroupLedger groupLedger;
    private final Config config;
    @Autowired
    ApplicationContext appCtx;
    private Connection connection;
    private boolean initialised = false;
    private String peerId;

    @Autowired
    public H2ObjectStorage(Config config) {
        this.config = config;
        this.groupLedger = GroupLedger.getInstance();
    }

    @Bean(name = "encryptorBean")
    public PooledPBEStringEncryptor encryptor() {
        PooledPBEStringEncryptor encryptor = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig config = new SimpleStringPBEConfig();
        config.setPassword("EnterTheMatrix");
        config.setAlgorithm("PBEWithMD5AndDES");
        config.setKeyObtentionIterations("1000");
        config.setPoolSize("1");
        config.setProviderName("SunJCE");
        config.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        config.setStringOutputType("base64");
        encryptor.setConfig(config);
        return encryptor;
    }

    @Override
    public void init() throws SQLException {
        //gameObjectsH2Repository.deleteAll();
        Environment environment = appCtx.getBean(Environment.class);
        logger.info("Starting H2 client connection ...");
        this.connection = DriverManager.getConnection(
                Objects.requireNonNull(environment.getProperty("spring.datasource.jdbcUrl")),
                Objects.requireNonNull(environment.getProperty("spring.datasource.username")),
                Objects.requireNonNull(environment.getProperty("spring.datasource.password"))); // encrypt credentials
        this.initialised = true;
        peerId = config.getNetworkHostnames().getGroupStorageServer(); // right now we use the hostname
    }

    @Override
    public void close() {
        logger.info("Closing H2 client connection ...");
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.error("Unable to close H2 client connection!");
        }
        this.initialised = false;
    }

    /**
     * The TTL is stored as creation time + ttl
     * <p>
     * TTL is received as long so no need for conversion - TimeUnit.SECONDS.toSeconds()
     */
    @Override
    public boolean put(GameObject object) {
        String objectId = object.getId();

        logger.debug("Adding object: {}", objectId);
        long creationTime = object.getCreationTime();
        long lastModified = object.getLastModified();
        long TTL = object.getTtl();

        String sql = "INSERT INTO gameobjects (id, creation_time, last_modified, ttl, value) VALUES (?, ?, ?, ?, ?)";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setString(1, objectId);
            statement.setLong(2, creationTime);
            statement.setLong(3, lastModified);
            statement.setLong(4, TTL);
            statement.setBytes(5, object.getValue());
            statement.executeUpdate();
            statement.close();
            logger.debug("Put successful!");

            addToGroupLedger(objectId, TTL);

            return true;
        } catch (SQLException e) {
            logger.warn("Put failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The TTL is stored as creation time + ttl
     */
    @Override
    public GameObject get(String key) throws NoSuchElementException {
        logger.debug("Retrieving object: {}", key);
        GameObject gameObject = null;

        String sqlStatement = "SELECT * FROM gameobjects WHERE id=?";

        try {
            PreparedStatement statement = connection.prepareStatement(sqlStatement);
            statement.setString(1, key);
            ResultSet resultSet = statement.executeQuery();

            while (resultSet.next()) {
                gameObject = GameObject.builder()
                        .id(resultSet.getString(1))
                        .creationTime(resultSet.getLong(2))
                        .lastModified(resultSet.getLong(3))
                        .ttl(resultSet.getLong(4))
                        .value(resultSet.getBytes(5))
                        .build();
            }
            statement.close();
        } catch (SQLException e) {
            logger.error("Get failed: {}", e.getMessage());
            // e.printStackTrace();
        }

        if (gameObject == null) {
            throw new NoSuchElementException(key);
        }

        if (isMalicious()) {
            logger.debug("WARNING: Malicious Peer, payload value will be altered!");
            gameObject = GameObject.builder()
                    .id("ohno")
                    .creationTime(gameObject.getCreationTime())
                    .ttl(gameObject.getTtl())
                    .value(Base64Utility.randomBase64(1024))
                    .build();
        }

        logger.debug("Get successful!");
        return gameObject;
    }

    /**
     * The TTL is stored as creation time + ttl
     * Technically we don't want creation time to be altered - this can be improved
     */
    @Override
    public boolean update(GameObject object) throws NoSuchElementException {
        String id = object.getId();
        boolean isPut = !groupLedger.objectLedgerContainsKey(id);

        logger.debug("Updating object: {}", id);
        long timestamp = object.getLastModified();
        long TTL = object.getTtl();

        if (isPut) {
            return put(object);
        }

        String sql = "UPDATE gameobjects SET ttl=?, last_modified=?, value=? WHERE id=?";

        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, TTL);
            statement.setLong(2, timestamp);
            statement.setBytes(3, object.getValue());
            statement.setString(4, id);
            int updateResult = statement.executeUpdate();
            if (updateResult == 0) {
                throw new NoSuchElementException(id);
            }
            statement.close();
            logger.debug("Update successful!");

            return true;
        } catch (SQLException e) {
            logger.error("Update failed: {}", e.getMessage());
            // e.printStackTrace();
            return false;
        }
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

    @Override
    public boolean delete(String key) throws NoSuchElementException {
        logger.debug("Deleting object: {}", key);
        String sqlStatement = "DELETE FROM gameobjects WHERE id=?";

        try {
            PreparedStatement statement = connection.prepareStatement(sqlStatement);
            statement.setString(1, key);
            int deleteResult = statement.executeUpdate();
            if (deleteResult == 0) {
                throw new NoSuchElementException(key);
            }
            statement.close();
            logger.debug("Delete successful!");
            return true;
        } catch (SQLException e) {
            logger.error("Delete failed: {}", e.getMessage());
            // e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isInitialised() {
        return initialised;
    }

    @Override
    public void truncate() {
        if (initialised) {
            logger.debug("Truncating gameobjects table...");
            String sqlStatement = "TRUNCATE TABLE gameobjects";

            try {
                PreparedStatement statement = connection.prepareStatement(sqlStatement);

                boolean truncate = statement.execute();
                if (truncate) {
                    logger.debug("Nothing to truncate");
                }
                statement.close();
                logger.debug("Truncate successful!");
            } catch (SQLException e) {
                logger.error("Truncate failed: {}", e.getMessage());
                // e.printStackTrace();
            }
        } else {
            logger.debug("Not truncating table, not initialised...");
        }
    }

    @Scheduled(fixedRateString = "${spring.schedule.dbCleanup}", initialDelay = 60000)
    public void cleanUp() {
        if (initialised) {
            logger.debug("Running scheduled cleanup...");
            long now = Instant.now().getEpochSecond();
            String sqlStatement = "DELETE FROM gameobjects WHERE ttl < ?";

            try {
                PreparedStatement statement = connection.prepareStatement(sqlStatement);
                statement.setLong(1, now);

                int deleteResult = statement.executeUpdate();
                if (deleteResult == 0) {
                    logger.debug("Nothing to clean");
                } else {
                    logger.debug("{} rows cleaned", deleteResult);
                }
                statement.close();
                logger.debug("Cleanup successful!");
            } catch (SQLException e) {
                logger.error("Cleanup failed: {}", e.getMessage());
                // e.printStackTrace();
            }
        } else {
            logger.debug("Not Running scheduled cleanup, not initialised...");
        }
    }

    public void initialiseTable() throws SQLException {
        final long unixTime = Instant.now().getEpochSecond();
        logger.debug("Populating H2 table ...");

        Statement statement1 = connection.createStatement();

        statement1.execute("DROP TABLE IF EXISTS gameobjects");
        statement1.execute("CREATE TABLE gameobjects (id VARCHAR(36) PRIMARY KEY,creation_time BIGINT NOT NULL, last_modified BIGINT NOT NULL, ttl BIGINT NOT NULL,value BINARY NOT NULL)");
        statement1.close();
    }

    @Override
    public boolean isMalicious() {
        return config.getMalicious();
    }
}
