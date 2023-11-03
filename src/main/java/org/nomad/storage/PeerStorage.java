package org.nomad.storage;

import it.unimi.dsi.fastutil.objects.HashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import lombok.Data;
import org.nomad.config.Config;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.pithos.components.GenericGroupLedger;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.group.GroupStorage;
import org.nomad.storage.local.LocalStorage;
import org.nomad.storage.overlay.DHTOverlayStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Data
@Service
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class PeerStorage {
    private final Logger logger = LoggerFactory.getLogger(PeerStorage.class);
    private final LocalStorage authoritativeObjectStore;
    private final DHTOverlayStorage dhtOverlayStorage;
    private final GroupStorage groupStorage;
    private final Config configuration;
    private GenericGroupLedger groupLedger;
    private String RETRIEVAL_MODE;
    private String STORAGE_MODE;
    private int overlayPort;
    private boolean initialized = false;

    @Autowired
    public PeerStorage(LocalStorage localStorage, DHTOverlayStorage dhtOverlayStorage, GroupStorage groupStorage, Config configuration) {
        this.authoritativeObjectStore = localStorage;
        this.dhtOverlayStorage = dhtOverlayStorage;
        this.groupStorage = groupStorage;
        this.groupLedger = GroupLedger.getInstance();
        this.configuration = configuration;
    }

    protected void setGroupLedger(GroupLedger ledger) {
        groupLedger = ledger;
    }

    /**
     * Initialize storage components:
     * - Local storage
     * - DHT Overlay storage
     * - Group Storage (GRPC server to handle group storage commands)
     *
     * @throws Exception when initialization fails
     */
    public void init(int groupStoragePort, SuperPeerClient superPeerClient) throws Exception {
        logger.info("Initializing storage components ...");
        authoritativeObjectStore.init();
        groupStorage.init(groupStoragePort, superPeerClient);
        initialized = true;
        RETRIEVAL_MODE = configuration.getStorage().getRetrievalMode();
        STORAGE_MODE = configuration.getStorage().getStorageMode();
    }

    public void updateGroupStorageSuperPeerClient(SuperPeerClient superPeerClient) {
        groupStorage.updateSuperPeerClient(superPeerClient);
    }

    public void close() throws SQLException, InterruptedException {
        if (initialized) {
            logger.info("Closing all storage Connections ...");
            authoritativeObjectStore.close();
            groupStorage.closeClients();
            initialized = false;
        }
    }

    public void updateGroupStorageClients(ArrayList<String> clients) {
        groupStorage.updateClients(clients);
    }

    public void notifyAllPeersRemovePeerFromGroupLedger() throws InterruptedException {
        groupStorage.notifyAllPeersRemovePeerFromGroupLedger();
    }

    public GameObject get(String key, boolean groupStorageEnabled, boolean overlayStorageEnabled) throws NoSuchElementException, InterruptedException {
        GameObject resultObject;

        if (groupLedger.objectLedgerContainsKey(key)) {
            logger.debug("found in group-ledger");
            try {
                resultObject = authoritativeObjectStore.get(key);
                return resultObject;
            } catch (NoSuchElementException e) {
                logger.debug("Key: {} not found locally, checking group & overlay storage", key);

                ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                ObjectOpenHashSet<Callable<GameObject>> callable = new ObjectOpenHashSet<>();

                if (overlayStorageEnabled) {
                    callable.add(() -> dhtOverlayStorage.get(key));
                }

                if (groupStorageEnabled) {
                    switch (RETRIEVAL_MODE) {
                        case "fast": {
                            callable.add(() -> groupStorage.fastGet(key));
                            try {
                                resultObject = executorService.invokeAny(callable, 2500, TimeUnit.MILLISECONDS);
                                executorService.shutdown();
                                return resultObject;
                            } catch (InterruptedException | TimeoutException | ExecutionException ex) {
                                logger.warn("{} Not found!", key);
                                logger.warn(ex.getMessage());
                                executorService.shutdownNow();
                                throw new NoSuchElementException();
                            }
                        }
                        case "parallel": {
                            callable.add(() -> groupStorage.parallelGet(key));
                            try {
                                resultObject = executorService.invokeAny(callable, 2500, TimeUnit.MILLISECONDS);
                                executorService.shutdown();
                                return resultObject;
                            } catch (InterruptedException | TimeoutException | ExecutionException ex) {
                                logger.warn("{} Not found!", key);
                                logger.warn(ex.getMessage());
                                executorService.shutdownNow();
                                throw new NoSuchElementException();
                            }
                        }
                        case "safe": {
                            callable.add(() -> groupStorage.safeGet(key));
                            ArrayList<Future<GameObject>> futures = new ObjectArrayList<>(executorService.invokeAll(callable, 2500, TimeUnit.MILLISECONDS));
                            executorService.shutdown();
                            resultObject = finalQuorum(futures);
                            if (resultObject != null) {
                                return resultObject;
                            }
                            throw new NoSuchElementException();
                        }
                    }
                } else {
                    logger.warn("Group-Storage is disabled!");
                    try {
                        resultObject = executorService.invokeAny(callable, 2500, TimeUnit.MILLISECONDS);
                        executorService.shutdown();
                        return resultObject;
                    } catch (InterruptedException | TimeoutException | ExecutionException ex) {
                        logger.warn("{} Not found!", key);
                        logger.warn(ex.getMessage());
                        executorService.shutdownNow();
                        throw new NoSuchElementException();
                    }
                }
            }
        } else if (overlayStorageEnabled) {
            logger.debug("Not found in group-ledger, executing Overlay call");
            try {
                resultObject = dhtOverlayStorage.get(key);
                return resultObject;
            } catch (ClassNotFoundException | NoSuchElementException | IOException | InterruptedException e) {
                logger.debug("{} Not found in overlay!", key);
                throw new NoSuchElementException();
            }
        }
        throw new NoSuchElementException();
    }

    public boolean put(GameObject object, boolean groupStorageEnabled, boolean overlayStorageEnabled) throws DuplicateKeyException, InterruptedException, IOException {
        if (groupLedger.thisPeerContainsObject(configuration.getNetworkHostnames().getGroupStorageServer(), object.getId())) {
            throw new DuplicateKeyException("Object with id: " + object.getId() + " already exists!");
        }

        if (object.getLastModified() == 0) {
            object.setLastModified(object.getCreationTime());
        }

        boolean localPut = authoritativeObjectStore.put(object);

        if (localPut) {
            groupStorage.notifyAllPeersObjectAdded(object.getId(), object.getTtl());

            ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();

            if (overlayStorageEnabled) {
                // ensure that at least at group level the object isn't stored in overlay more than once
                callable.add(() -> dhtOverlayStorage.put(object));
            } else {
                logger.warn("Not adding to overlay!");
            }

            if (groupStorageEnabled) {
                switch (STORAGE_MODE) {
                    case "fast": {
                        callable.add(() -> groupStorage.fastPut(object));
                        try {
                            boolean result = executorService.invokeAny(callable, 2500, TimeUnit.MILLISECONDS);
                            executorService.shutdown();
                            return result;
                        } catch (ExecutionException e) {
                            logger.error("Put failed");
                            return false;
                        } catch (TimeoutException e) {
                            logger.error("Put timed out");
                            return false;
                        }
                    }
                    case "safe":
                    default: {
                        callable.add(() -> groupStorage.safePut(object));
                        ArrayList<Future<Boolean>> futures = new ObjectArrayList<>(executorService.invokeAll(callable, 2500, TimeUnit.MILLISECONDS));
                        executorService.shutdown();
                        boolean allSuccess = allRequestsSucceeded(futures);

                        if (allSuccess) {
                            logger.debug("All storage commands were successful");
                        } else {
                            logger.error("Not all storage commands were successful");
                        }
                        return allSuccess;
                    }
                }
            } else {
                logger.warn("Group-Storage is disabled!");
                try {
                    boolean result = executorService.invokeAny(callable, 2500, TimeUnit.MILLISECONDS);
                    executorService.shutdown();
                    return result;
                } catch (ExecutionException e) {
                    logger.error("Put failed");
                    return false;
                } catch (TimeoutException e) {
                    logger.error("Put timed out");
                    return false;
                }
            }
        }

        return false;
    }

    public boolean update(GameObject object) throws InterruptedException, NoSuchElementException {
        boolean isPut = !groupLedger.objectLedgerContainsKey(object.getId());

        if (object.getLastModified() == 0) {
            object.setLastModified(Instant.now().getEpochSecond());
        }

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();

        callable.add(() -> authoritativeObjectStore.update(object));
        callable.add(() -> groupStorage.safeUpdate(object));
        callable.add(() -> dhtOverlayStorage.update(object));

        ArrayList<Future<Boolean>> futures = new ObjectArrayList<>(executorService.invokeAll(callable, 2500, TimeUnit.MILLISECONDS));
        executorService.shutdown();

        boolean allSuccess = allRequestsSucceeded(futures);

        if (allSuccess || isPut) {
            // only notify peer if the object was indeed added
            long TTL = object.getTtl() - object.getCreationTime();
            long newTTl = object.getLastModified() + TTL;
            groupStorage.notifyAllPeersObjectAdded(object.getId(), newTTl);
        }

        return allSuccess;
    }

    private boolean allRequestsSucceeded(ArrayList<Future<Boolean>> futures) throws InterruptedException {
        int successfulResults = 0;
        int goal = futures.size();
        for (Future<Boolean> future : futures) {
            if (!future.isCancelled()) {
                try {
                    if (future.isDone()) {
                        if (future.get()) {
                            successfulResults++;
                        }
                    } else {
                        if (future.get(200, TimeUnit.MILLISECONDS)) {
                            successfulResults++;
                        }
                    }
                } catch (ExecutionException | TimeoutException e) {
                    logger.error("Execution Failed or Timed out. See stacktrace");
                    // e.printStackTrace();
                }
            } else {
                logger.warn("Cancelled!");
            }
        }

        logger.debug("Successful results: {}/{}", successfulResults, goal);
        return successfulResults == goal;
    }

    /**
     * Receives futures from overlay and group storage and compares the results for a final quorum
     *
     * @param futures
     * @return
     */
    protected GameObject finalQuorum(ArrayList<Future<GameObject>> futures) {
        HashMap<GameObject, Integer> quorumMap = new HashMap<>();
        int successfulResults = 0;
        for (Future<GameObject> future : futures) {
            if (!future.isCancelled()) {
                try {
                    GameObject retrievedGameObject;
                    if (future.isDone()) {
                        retrievedGameObject = future.get();
                    } else {
                        retrievedGameObject = future.get(100, TimeUnit.MILLISECONDS);
                    }
                    logger.debug("Get result: {}", retrievedGameObject);
                    quorumMap.merge(retrievedGameObject, 1, Integer::sum);
                    successfulResults++;
                } catch (ExecutionException e) {
                    // this will happen if the peer does not store this object
                    logger.error("Failed to get result", e);
                } catch (InterruptedException e) {
                    logger.error("Get Interrupted", e);
                    Thread.currentThread().interrupt();
                } catch (TimeoutException e) {
                    logger.error("Get timed out!", e);
                }
            }
        }

        Map.Entry<GameObject, Integer> max = Collections.max(quorumMap.entrySet(), Comparator.comparingInt(Map.Entry::getValue));
        GameObject finalResult = max.getKey();
        int consistentResults = max.getValue();

        if (consistentResults <= 1) {
           return null;
        }

        logger.debug("{}/{} successful results were consistent", consistentResults, successfulResults);
        logger.debug("Get result: {}", finalResult);
        return finalResult;
    }

    // TODO do I need this?
    public boolean delete(String key) throws NoSuchElementException, InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();

        Boolean localDelete = callable.add(() -> authoritativeObjectStore.delete(key));
        Boolean groupDelete = callable.add(() -> groupStorage.safeDelete(key));
        Boolean overlayDelete = callable.add(() -> dhtOverlayStorage.delete(key));

        executorService.invokeAll(callable, 2500, TimeUnit.MILLISECONDS);
        executorService.shutdown();

        // TODO: notify peers and super Peer that an object was added

        logger.debug("local put: {}, group put: {}, overlay put: {}", localDelete, groupDelete, overlayDelete);
        return localDelete && overlayDelete && groupDelete;
    }

    public boolean localPut(GameObject object) throws InterruptedException {
        String objectId = object.getId();
        if (!groupLedger.thisPeerContainsObject(configuration.getNetworkHostnames().getGroupStorageServer(), objectId)) {
            boolean result = authoritativeObjectStore.put(object);
            if (result) {
                groupStorage.notifyAllPeersObjectAdded(objectId, object.getTtl());
            }
            return result;
        } else {
            logger.warn("Object {} already in authoritative object-store!", objectId);
            // might not be required
            groupStorage.notifyAllPeersObjectAdded(objectId, object.getTtl());
            return false;
        }
    }

    public void setRetrievalMode(String retrievalMode) {
        logger.info("Retrieval mode updated: {}", retrievalMode);
        this.RETRIEVAL_MODE = retrievalMode;
    }

    public void setStorageMode(String storageMode) {
        logger.info("Storage mode updated: {}", storageMode);
        this.STORAGE_MODE = storageMode;
    }

    public void setRF() {
        logger.info("RF updated!");
        groupStorage.updateReplicationFactor();
    }

    public void truncateAuthoritativeObjectStore() {
        authoritativeObjectStore.truncate();
    }
}