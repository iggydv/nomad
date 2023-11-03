package org.nomad.storage.group;

import com.google.common.collect.Sets;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import it.unimi.dsi.fastutil.objects.HashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import it.unimi.dsi.fastutil.objects.ArrayLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.nomad.config.Config;
import org.nomad.grpc.management.clients.GroupStorageClient;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.servers.GroupStorageServer;
import org.nomad.pithos.components.GenericGroupLedger;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.QuorumException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@DependsOn("ZookeeperDirectoryServerClient")
public class GroupStorage {
    private final Logger logger = LoggerFactory.getLogger(GroupStorage.class);
    private final GroupStorageServer server;
    private final Config configuration;
    private final GenericGroupLedger groupLedger;
    private final HashMap<String, GroupStorageClient> clientsMap = new HashMap<>();
    private final Random random = new Random();
    private boolean initialized = false;
    private SuperPeerClient superPeerClient;
    private int replicationFactor;

    @Autowired
    public GroupStorage(GroupStorageServer groupStorageServer, Config configuration) {
        this.server = groupStorageServer;
        this.configuration = configuration;
        this.groupLedger = GroupLedger.getInstance();
    }

    /**
     * Should always be called first
     *
     * @param port to be used for the server
     * @throws IOException if initialization fails
     */
    public void init(int port, SuperPeerClient sp) throws IOException {
        // Initialize the server
        logger.debug("Group Storage port: {}", port);
        superPeerClient = sp;
        server.init(port, sp);
        server.start();
        replicationFactor = configuration.getStorage().getReplicationFactor();
        updateClients(ArrayLists.emptyList());
        initialized = true;
    }

    public void updateSuperPeerClient(SuperPeerClient superPeerClient) {
        this.superPeerClient = superPeerClient;
        server.updateSuperPeerClient(superPeerClient);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void checkClient(String hostname) {
        if (initialized) {
            logger.debug("Checking client {} health", hostname);
            boolean activeClient = checkHealthWithRetry(clientsMap.get(hostname));
            if (!activeClient) {
                clientsMap.remove(hostname);
            }
        }
    }

    private boolean checkHealthWithRetry(GroupStorageClient client) {
        boolean healthCheckResult = client.healthCheck();

        if (healthCheckResult) {
            logger.debug("Health check succeeded, client at {} is healthy!", client.getClientHostname());
            // return false to the 'removeIf' function
            return false;
        } else {
            logger.warn("Health check failed, removing {}", client.getClientHostname());
            try {
                client.close();
            } catch (InterruptedException e) {
                logger.error("Failed to close client! Please see stacktrace for more information");
                Thread.currentThread().interrupt();
                // e.printStackTrace();
            }
            // return true to the 'removeIf' function
            return true;
        }
    }

    public ObjectOpenHashSet<String> getClients() {
        return new ObjectOpenHashSet<>(clientsMap.keySet());
    }

    // Used only in testing
    public void setClients(ArrayList<String> clients) {
        logger.debug("Received list: {}", clients);
        clients.forEach(target -> {
            if (!target.equals(server.getHost()) && !target.isEmpty() && !clientsMap.containsKey(target)) {
                logger.debug("Adding target: {}", target);
                ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().keepAliveTime(300, TimeUnit.SECONDS).build();
                GroupStorageClient client = new GroupStorageClient(channel);
                clientsMap.put(target, client);
            }
        });
    }

    public void updateClients(ArrayList<String> clients) {
        logger.debug("Received list: {}", clients);
        logger.debug("Updating group-storage clients for {}...", server.getHost());

        if (clients.isEmpty()) {
            clientsMap.values().forEach(client -> {
                try {
                    logger.info("Closing client: {}", client);
                    client.close();
                } catch (InterruptedException e) {
                    logger.error("Failed to close group-storage client: {}", client.getClientHostname());
                    Thread.currentThread().interrupt();
                    // e.printStackTrace();
                }
            });
            clientsMap.clear();
            server.updateAvailableClients(new ObjectArrayList<>());
            return;
        }

        ObjectOpenHashSet<String> sources = new ObjectOpenHashSet<>(clientsMap.keySet());
        ObjectOpenHashSet<String> targets = new ObjectOpenHashSet<>(clients);

        // Everything contained in sources but not in target
        Sets.SetView<String> clientsToRemove = Sets.difference(sources, targets);
        logger.debug("Clients to remove: {}", clientsToRemove);
        Sets.SetView<String> clientsToAdd = Sets.difference(targets, sources);
        logger.debug("Clients to add: {}", clientsToAdd);


        clientsMap.forEach((key, client) -> {
            if (clientsToRemove.contains(key)) {
                try {
                    client.close();
                } catch (InterruptedException e) {
                    logger.error("Failed to close group-storage client: {}", key);
                    Thread.currentThread().interrupt();
                }
            }
        });

        clientsMap.keySet().removeAll(clientsToRemove);

        clientsToAdd.forEach(clientToAdd -> {
            if (!clientToAdd.equals(server.getHost()) && !clientToAdd.isEmpty()) {
                logger.debug("Adding target: {}", clientToAdd);
                ManagedChannel channel = ManagedChannelBuilder.forTarget(clientToAdd).usePlaintext().keepAliveTime(300, TimeUnit.SECONDS).build();
                GroupStorageClient client = new GroupStorageClient(channel);
                clientsMap.put(clientToAdd, client);
            }
        });

        server.updateAvailableClients(new ObjectArrayList<>(clientsMap.values()));
    }

    public GroupStorageClient getClient(String client) {
        return clientsMap.get(client);
    }

    public void closeClients() throws InterruptedException {
        notifyAllPeersRemovePeerFromGroupLedger();
        this.clientsMap.values().forEach(groupStorageClient -> {
            try {
                groupStorageClient.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while closing client gRPC connection!");
            }
        });
        clientsMap.clear();
        server.stop();
        initialized = false;
    }

    // Used only in testing
    protected void clearMap() {
        this.clientsMap.values().forEach(groupStorageClient -> {
            try {
                groupStorageClient.close();
            } catch (InterruptedException e) {
                logger.error("Interrupted while closing client gRPC connection!");
                Thread.currentThread().interrupt();
            }
        });
        clientsMap.clear();
    }

    /**
     * Send out storage request and return after first successful request
     *
     * @return true one requests succeeded,
     * false all put requests failed
     **/
    public boolean fastPut(GameObject gameObject) throws IOException {
        logger.debug("fast put");
        boolean result;

        if (clientsMap.values().isEmpty()) {
            logger.warn("No additional group members...");
            return true;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ArrayList<String> clientHostnames = new ObjectArrayList<>(clientsMap.keySet());
        Collections.shuffle(clientHostnames, random);
        ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();
        int replicaCount = 0;

        for (String hostname : clientHostnames) {
            if (replicaCount < replicationFactor - 1) {
                GroupStorageClient client = clientsMap.get(hostname);
                callable.add(() -> client.putGameObjectFuture(gameObject));
                replicaCount++;
            }
        }

        try {
            result = executorService.invokeAny(callable, 5, TimeUnit.SECONDS);
            executorService.shutdown();
            return result;
        } catch (InterruptedException e) {
            logger.error("Group put interrupted!");
            Thread.currentThread().interrupt();
            // e.printStackTrace();
            return false;
        } catch (ExecutionException | TimeoutException e) {
//            e.printStackTrace();
            return false;
        }
    }

    /**
     * Store on #<replicationFactor> nodes
     *
     * @return true all requests succeeded,
     * false one request failed
     **/
    public boolean safePut(GameObject gameObject) throws IllegalStateException {
        logger.debug("safe put");
        ArrayList<Future<Boolean>> results;
        int successfulResults = 0;
        if (clientsMap.values().isEmpty()) {
            logger.warn("No additional group members...");
            return true;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ArrayList<String> clientHostnames = new ObjectArrayList<>(clientsMap.keySet());
        Collections.shuffle(clientHostnames, random);
        ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();
        int replicaCount = 0;

        for (String hostname : clientHostnames) {
            if (replicaCount < replicationFactor - 1) {
                GroupStorageClient client = clientsMap.get(hostname);
                callable.add(() -> client.putGameObjectFuture(gameObject));
                replicaCount++;
            }
        }

        try {
            results = new ObjectArrayList<>(executorService.invokeAll(callable, 5, TimeUnit.SECONDS));
            executorService.shutdown();
            logger.debug("{} replicas created.", replicaCount);
            for (Future<Boolean> future : results) {
                if (!future.isCancelled()) {
                    try {
                        if (future.get()) {
                            // Put was successful, now add to the list
                            successfulResults++;
                        }
                    } catch (ExecutionException e) {
                        logger.error("Failed to get result", e);
                    } catch (InterruptedException e) {
                        logger.error("Interrupted", e);
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return quorum(successfulResults);
        } catch (InterruptedException e) {
            logger.error("Thread was interrupted!");
            Thread.currentThread().interrupt();
            // e.printStackTrace();
            return false;
        }
    }

    /**
     * Send out multiple Get commands, return the first successful result
     *
     * @return GameObject from one node in group storage
     * @throws NoSuchElementException if no element is found
     **/
    public GameObject parallelGet(String id) throws NoSuchElementException {
        if (clientsMap.values().isEmpty()) {
            logger.warn("No additional group members...");
            throw new NoSuchElementException();
        }

        GameObject resultObject;

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ObjectOpenHashSet<Callable<GameObject>> callable = new ObjectOpenHashSet<>();
        ArrayList<String> peersThatStoreObject = groupLedger.removePeersNotStoringObject(clientsMap.keySet(), id);
        Collections.shuffle(peersThatStoreObject, random);

        peersThatStoreObject.forEach(clientHostname -> {
            GroupStorageClient client = clientsMap.get(clientHostname);
            callable.add(() -> client.getGameObjectFuture(id));
        });

        try {
            resultObject = executorService.invokeAny(callable, 5, TimeUnit.SECONDS);
            executorService.shutdown();
            logger.debug("Get result: {}", resultObject);
            return resultObject;
        } catch (TimeoutException | ExecutionException e) {
            logger.error("All gets failed!");
            executorService.shutdownNow();
            throw new NoSuchElementException();
        } catch (InterruptedException e) {
            logger.warn("Gets interrupted!");
            Thread.currentThread().interrupt();
            throw new NoSuchElementException();
        }
    }

    /**
     * Send out one Get command to a single client
     *
     * @return GameObject from one node in group storage
     * @throws NoSuchElementException if no element is found
     **/
    public GameObject fastGet(String id) throws NoSuchElementException {
        if (clientsMap.values().isEmpty()) {
            logger.warn("No additional group members...");
            throw new NoSuchElementException();
        }

        ArrayList<String> peersThatStoreObject = groupLedger.removePeersNotStoringObject(clientsMap.keySet(), id);
        Collections.shuffle(peersThatStoreObject, random);
        if (!peersThatStoreObject.isEmpty()) {
            GroupStorageClient client = clientsMap.get(peersThatStoreObject.iterator().next());
            try {
                return client.getGameObjectFuture(id);
            } catch (InterruptedException e) {
                logger.warn("Gets interrupted!");
                Thread.currentThread().interrupt();
                throw new NoSuchElementException();
            }
        }
        throw new NoSuchElementException();
    }

    /**
     * Get from all nodes containing the object
     *
     * @return {@link GameObject} returned by most nodes,
     * null otherwise
     **/
    public GameObject safeGet(String id) throws IOException, QuorumException {

        if (clientsMap.values().isEmpty()) {
            logger.warn("No additional group members...");
            throw new NoSuchElementException();
        }

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ArrayList<Future<GameObject>> resultObjects;
        ObjectOpenHashSet<Callable<GameObject>> callable = new ObjectOpenHashSet<>();
        HashMap<GameObject, Integer> quorumMap = new HashMap<>();
        ArrayList<String> peersThatStoreObject = groupLedger.removePeersNotStoringObject(clientsMap.keySet(), id);
        Collections.shuffle(peersThatStoreObject, random);
        int replicaCount = 0;
        int successfulResults = 0;

        for (String clientHostname : peersThatStoreObject) {
            if (replicaCount <= replicationFactor - 1) {
                GroupStorageClient client = clientsMap.get(clientHostname);
                callable.add(() -> client.getGameObjectFuture(id));
                replicaCount++;
            }
        }

        try {
            resultObjects = new ObjectArrayList<>(executorService.invokeAll(callable, 5, TimeUnit.SECONDS));
            executorService.shutdown();
            for (Future<GameObject> future : resultObjects) {
                if (!future.isCancelled()) {
                    try {
                        GameObject retrievedGameObject;
                        if (future.isDone()) {
                            retrievedGameObject = future.get();
                        } else {
                            retrievedGameObject = future.get(10, TimeUnit.MILLISECONDS);
                        }
                        logger.debug("Get result: {}", retrievedGameObject);
                        quorumMap.merge(retrievedGameObject, 1, Integer::sum);
                        successfulResults++;

                    } catch (ExecutionException e) {
                        // this will happen if the peer does not store this object
                        executorService.shutdown();
                        logger.error("Failed to get result", e);
                    } catch (InterruptedException e) {
                        logger.error("Interrupted", e);
                        executorService.shutdown();
                        Thread.currentThread().interrupt();
                    }
                }
            }

            Map.Entry<GameObject, Integer> max = Collections.max(quorumMap.entrySet(), Comparator.comparingInt(Map.Entry::getValue));
            GameObject finalResult = max.getKey();
            int consistentResults = max.getValue();

            logger.debug("{}/{} successful results were consistent", consistentResults, successfulResults);
            logger.debug("Get result: {}", finalResult);

            if (!quorum(consistentResults)) {
                logger.error("Quorum was not reached!");
                throw new QuorumException();
            }
            return finalResult;

        } catch (InterruptedException | TimeoutException e) {
            logger.error("All gets failed!");
            executorService.shutdownNow();
            throw new NoSuchElementException();
        }
    }

    /**
     * Update on all nodes
     *
     * @return true all requests succeeded,
     * false one request failed
     **/
    public boolean safeUpdate(GameObject gameObject) throws IOException {
        logger.debug("safe put");
        ArrayList<Future<Boolean>> results;
        int successfulResults = 0;
        if (clientsMap.values().isEmpty()) {
            logger.warn("No additional group members...");
            return true;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ArrayList<String> clientHostnames = new ObjectArrayList<>(clientsMap.keySet());
        Collections.shuffle(clientHostnames, random);
        ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();
        int replicaCount = 0;

        for (String hostname : clientHostnames) {
            if (replicaCount < replicationFactor - 1) {
                GroupStorageClient client = clientsMap.get(hostname);
                callable.add(() -> client.updateGameObjectFuture(gameObject));
                replicaCount++;
            }
        }

        try {
            results = new ObjectArrayList<>(executorService.invokeAll(callable, 5, TimeUnit.SECONDS));
            executorService.shutdown();
            logger.debug("{} replicas created.", replicaCount);
            for (Future<Boolean> future : results) {
                if (!future.isCancelled()) {
                    try {
                        if (future.get()) {
                            // Put was successful, now add to the list
                            successfulResults++;
                        }
                    } catch (ExecutionException e) {
                        logger.error("Failed to get result", e);
                    } catch (InterruptedException e) {
                        logger.error("Interrupted", e);
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return quorum(successfulResults);
        } catch (InterruptedException e) {
            logger.error("Thread was interrupted!");
            Thread.currentThread().interrupt();
            // e.printStackTrace();
            return false;
        }
    }

    /**
     * Update on one node
     *
     * @return true one requests succeeded,
     * false all requests failed
     **/
    @Deprecated
    public boolean fastUpdate(GameObject gameObject) throws IOException {
        logger.debug("fast update");
        boolean result;

        if (clientsMap.values().isEmpty()) {
            logger.warn("No additional group members...");
            return true;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        ArrayList<String> clientHostnames = new ObjectArrayList<>(clientsMap.keySet());
        Collections.shuffle(clientHostnames, random);
        ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();
        int replicaCount = 0;

        for (String hostname : clientHostnames) {
            if (replicaCount < replicationFactor - 1) {
                GroupStorageClient client = clientsMap.get(hostname);
                callable.add(() -> client.updateGameObjectFuture(gameObject));
                replicaCount++;
            }
        }

        try {
            result = executorService.invokeAny(callable, 5, TimeUnit.SECONDS);
            executorService.shutdown();
            return result;
        } catch (InterruptedException e) {
            logger.error("Group put interrupted!");
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    /**
     * Delete on one node
     *
     * @return true one requests succeeded,
     * false all put requests failed
     **/
    @Deprecated
    public boolean fastDelete(String id) throws IOException {
        boolean result;
        if (!clientsMap.values().isEmpty()) {
            for (GroupStorageClient client : clientsMap.values()) {
                result = client.deleteGameObject(id);
                logger.debug("Delete result: {}", result);
                if (result) {
                    return true;
                }
            }
        }
        logger.debug("No additional group members...");
        return true;
    }

    /**
     * Delete on all nodes
     *
     * @return true all requests succeeded,
     * false one request failed
     **/
    @Deprecated
    public boolean safeDelete(String id) throws IOException {
        boolean result = false;
        if (!clientsMap.values().isEmpty()) {
            result = true;
            for (GroupStorageClient client : clientsMap.values()) {
                boolean thisResult = client.deleteGameObject(id);
                logger.debug("Delete result: {}", thisResult);
                result = result && thisResult;
            }
            return result;
        }
        logger.debug("No additional group members");
        return true;
    }


    public void notifyAllPeersObjectAdded(String objectId, long ttl) throws InterruptedException {
        String peerGroupStorageHostname = server.getHost();

        logger.debug("Notifying Super-Peer Object {} added...", objectId);
        superPeerClient.addObjectReferenceFuture(objectId, peerGroupStorageHostname, ttl);
        if (!clientsMap.values().isEmpty()) {
            for (GroupStorageClient client : clientsMap.values()) {
                if (client.isActive()) {
                    logger.debug("Notifying client: {}", client.getClientHostname());
                    client.notifyObjectAddedFuture(objectId, peerGroupStorageHostname, ttl);
                }
            }
        }
        logger.debug("All peers notified Object {} added...", objectId);
    }

    /**
     * Called when the peer-server shuts down
     */
    public void notifyAllPeersRemovePeerFromGroupLedger() throws InterruptedException {
        // Could also use the configuration
        String peerGroupStorageHostname = server.getHost();

        logger.debug("Notifying Super-Peer to remove {} from group-ledger...", peerGroupStorageHostname);
        superPeerClient.removePeerGroupLedgerFuture(peerGroupStorageHostname);

        if (!clientsMap.values().isEmpty()) {
            for (GroupStorageClient client : clientsMap.values()) {
                if (client.isActive()) {
                    client.notifyRemovePeerFromGroupLedgerFuture(peerGroupStorageHostname);
                }
            }
        }
        logger.debug("All peers notified to remove {} from group-ledger...", peerGroupStorageHostname);
    }

    /**
     * Quorum is a majority of replicas aka replication factor.
     * (sum_of_all_replicas/2+1 rounded down)
     *
     * @param successCounts
     * @return
     */
    protected boolean quorum(int successCounts) {
        int rf = replicationFactor - 1;
        if (rfReachable()) {
            int requiredSuccessForQuorum = (rf / 2) + 1;
            return successCounts >= requiredSuccessForQuorum;
        } else {
            int numClients = clientsMap.keySet().size();
            int requiredSuccessForQuorum = (numClients / 2) + 1;
            return successCounts >= requiredSuccessForQuorum;
        }
    }

    protected boolean rfReachable() {
        int numClients = clientsMap.keySet().size();
        return numClients >= (replicationFactor - 1);
    }

    public void updateReplicationFactor() {
        replicationFactor = configuration.getStorage().getReplicationFactor();
    }
}