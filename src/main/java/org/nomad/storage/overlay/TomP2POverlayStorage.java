package org.nomad.storage.overlay;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.tomp2p.connection.Bindings;
import net.tomp2p.dht.FutureGet;
import net.tomp2p.dht.FuturePut;
import net.tomp2p.dht.FutureRemove;
import net.tomp2p.dht.PeerBuilderDHT;
import net.tomp2p.dht.PeerDHT;
import net.tomp2p.dht.PutBuilder;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureBootstrap;
import net.tomp2p.futures.FutureDiscover;
import net.tomp2p.message.DataFilterTTL;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.storage.Data;
import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.pithos.models.GameObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Repository
@Profile("tomp2p")
public class TomP2POverlayStorage implements DHTOverlayStorage {
    private final Logger logger = LoggerFactory.getLogger(TomP2POverlayStorage.class);
    private final int bootstrapPort = 4001;
    private final Number160 tomP2PId;
    private PeerDHT peerDHT;
    private List<PeerAddress> peers = new ArrayList<>();
    private String bootstrapHostName;
    private int peerDHTPort;

    @Autowired
    public TomP2POverlayStorage(Config config) {
        this.tomP2PId = Number160.createHash(config.getPeerID());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close();
            } catch (InterruptedException e) {
                logger.error("Interrupted whilst closing overlay client!");
                Thread.currentThread().interrupt();
            }
        }));
    }

    @Override
    public int initOverlay() throws IOException {
        final RetryPolicy<Void> voidRetryPolicy = new RetryPolicy<Void>()
                .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()))
                .withBackoff(1, 30, ChronoUnit.SECONDS)
                .handle(UnknownHostException.class, ConnectException.class)
                .withMaxRetries(3);

        logger.info("Initializing TomP2P Overlay storage...");
        peerDHTPort = bootstrapPort;

        if (!NetworkUtility.available(peerDHTPort)) {
            logger.info("Assigning new port for Overlay...");
            peerDHTPort = NetworkUtility.randomPort(5001, 8999);
        }
        logger.debug("Creating TomP2P peer, with ID: {} Port {}", tomP2PId, peerDHTPort);

        createPeerDHT();

        Failsafe.with(voidRetryPolicy)
                .onFailure(e -> logger.error("failed to connect to bootstrap peer"))
                .onSuccess(e -> logger.info("connected to bootstrap peer"))
                .run(() -> connectToPeer(bootstrapHostName, peerDHTPort));

        return peerDHTPort;
    }

    @Override
    public int joinOverlay(String dhtHostname) throws IOException, InterruptedException {
        logger.debug("Initializing Overlay storage with dht Peer hostname: {}", dhtHostname);
        peerDHTPort = bootstrapPort;

        if (!NetworkUtility.available(peerDHTPort)) {
            logger.info("Assigning new port for Overlay...");
            peerDHTPort = NetworkUtility.randomPort(5001, 8999);
        }
        logger.debug("Creating TomP2P peer, with ID: {} Port {}", tomP2PId, peerDHTPort);

        createPeerDHT();
        String[] hostname = dhtHostname.split(":");
        String ip = hostname[0];
        int port = Integer.parseInt(hostname[1]);
        connectToPeer(ip, port);
        return peerDHTPort;
    }

    private void createPeerDHT() throws IOException {
        Bindings binding = new Bindings();
        bootstrapHostName = NetworkUtility.getIP();
        logger.debug("TomP2P binding hostname: {}", bootstrapHostName);
        binding.addAddress(InetAddress.getByName(bootstrapHostName))
                .addInterface(NetworkUtility.getNetworkInterface())
                .listenAny()
                .anyProtocols();

        Peer peer = new PeerBuilder(tomP2PId)
                .bindings(binding)
                .ports(peerDHTPort)
                .start();

        peerDHT = new PeerBuilderDHT(peer).start();
    }

    private void connectToPeer(String ip, int port) throws UnknownHostException, ConnectException {
        InetAddress address = Inet4Address.getByName(ip);
        logger.info("Discovering host bootstrap hostname: {}:{}", ip, port);
        FutureDiscover futureDiscover = peerDHT.peer().discover().inetAddress(address).ports(port).start();
        futureDiscover.addListener(new BaseFutureAdapter<FutureDiscover>() {
            @Override
            public void operationComplete(FutureDiscover future) throws Exception {
                if (future.isSuccess()) {
                    logger.info("Bootstrap hostname discovery successful!");
                } else {
                    logger.error("Bootstrap hostname discovery failed!");
                }
            }
        }).awaitListenersUninterruptibly();

        logger.info("Attempting bootstrap...");
        FutureBootstrap futureBootstrap = peerDHT.peer().bootstrap().inetAddress(address).ports(port).start();
        futureBootstrap.awaitUninterruptibly();

        if (futureBootstrap.isSuccess()) {
            logger.info("Bootstrap successful - Connected to the Overlay network");
        } else {
            logger.error(futureBootstrap.failedReason());
            throw new ConnectException("Bootstrap failed - Unable to connect to TomP2P bootstrap host");
        }

        peers = peerDHT.peer().peerBean().peerMap().all();
        for (PeerAddress peerAddress : peers) {
            peerDHT.peer().bootstrap().peerAddress(peerAddress).start().awaitUninterruptibly();
        }
    }

    @Override
    public GameObject get(String key) throws ClassNotFoundException, IOException, InterruptedException {
        logger.debug("Overlay Get: {}", key);
        DataFilterTTL filterTTL = new DataFilterTTL();

        Number160 overlayKey = Number160.createHash(key);
        GameObject resultGameObject;

        FutureGet futureGet = peerDHT.get(overlayKey).fastGet().start();
        futureGet.addListener(new BaseFutureAdapter<FutureGet>() {
            @Override
            public void operationComplete(FutureGet future) throws Exception {
                if (future.isSuccess()) { // this flag indicates if the future was successful
                    logger.debug("get was successful");
                } else {
                    logger.debug("get failed");
                }
            }
        });
        futureGet.awaitListeners();

        if (futureGet.isSuccess() && !futureGet.isEmpty()) {
            Data data = futureGet.data();
            filterTTL.filter(data, false, true);
            resultGameObject = (GameObject) futureGet.data().object();
            if (data.ttlSeconds() <= 0) {
                logger.debug("The data is expired and should be removed");
                data.deleted();
                peerDHT.remove(overlayKey).start();
                throw new NoSuchElementException();
            }
            return resultGameObject;
        } else {
            logger.debug("Get failed and futureGet was empty!");
        }
        throw new NoSuchElementException();
    }

    @Override
    public GameObject getUninterruptible(String key) throws ClassNotFoundException, IOException, InterruptedException {
        logger.debug("Overlay Get: {}", key);
        DataFilterTTL filterTTL = new DataFilterTTL();

        Number160 overlayKey = Number160.createHash(key);
        GameObject resultGameObject;

        FutureGet futureGet = peerDHT.get(overlayKey).fastGet().start().awaitUninterruptibly();

        if (futureGet.isSuccess() && !futureGet.isEmpty()) {
            Data data = futureGet.data();
            filterTTL.filter(data, false, true);
            resultGameObject = (GameObject) futureGet.dataMap().values().iterator().next().object();
            if (data.ttlSeconds() <= 0) {
                logger.debug("The data is expired and should be removed");
                data.deleted();
                peerDHT.remove(overlayKey).start();
                throw new NoSuchElementException();
            }
            return resultGameObject;
        }

        throw new NoSuchElementException();
    }

    @Override
    public boolean put(GameObject gameObject) throws IOException, InterruptedException {
        String id = gameObject.getId();
        logger.debug("Overlay Put {}", id);

        long TTL = calculateTTL(gameObject.getCreationTime(), gameObject.getTtl());

        Data data = new Data(gameObject).ttlSeconds(Math.toIntExact(TTL));
        Number160 overlayKey = Number160.createHash(id);
        logger.debug("TomP2P Put: {}, {}", overlayKey, gameObject);

        FuturePut futurePut = peerDHT.put(overlayKey).data(data).start();

        futurePut.addListener(new BaseFutureAdapter<FuturePut>() {
            @Override
            public void operationComplete(FuturePut future) throws Exception {
                if (future.isSuccess()) {
                    logger.debug("put was successful");
                } else {
                    logger.debug("put failed");
                }
            }
        });
        futurePut.awaitListeners();

        if (futurePut.isMinReached()) {
            logger.debug("Minimum amount of nodes reached");
        } else {
            logger.debug("Min nodes not reached");
        }
        return futurePut.isSuccess();
    }

    @Override
    public boolean putUninterruptible(GameObject gameObject) throws IOException, InterruptedException {
        String id = gameObject.getId();

        long TTL = calculateTTL(gameObject.getCreationTime(), gameObject.getTtl());
        logger.debug("Overlay Put {}", id);
        // TODO might need to update the TTL here!

        Data data = new Data(gameObject).ttlSeconds(Math.toIntExact(TTL));
        Number160 overlayKey = Number160.createHash(id);
        logger.debug("TomP2P Put: {}, {}", overlayKey, gameObject);
        FuturePut futurePut = peerDHT.put(overlayKey).data(data).putIfAbsent().start().awaitUninterruptibly();

        return futurePut.isSuccess();
    }

    @Override
    public boolean update(GameObject gameObject) throws IOException, InterruptedException {
        // TODO: if found remove first then re-add (deduplicate)
        String id = gameObject.getId();
        long TTL = calculateTTL(gameObject.getCreationTime(), gameObject.getTtl());


        Data data = new Data(gameObject).ttlSeconds(Math.toIntExact(TTL));
        logger.debug("Overlay Update {}", id);
        Number160 overlayKey = Number160.createHash(id);
        logger.debug("TomP2P Update: {}, {}", overlayKey, gameObject);
        FuturePut futurePut = peerDHT.put(overlayKey).data(data).start();

        futurePut.addListener(new BaseFutureAdapter<FuturePut>() {
            @Override
            public void operationComplete(FuturePut future) throws Exception {
                if (future.isSuccess()) {
                    logger.debug("put was successful");
                } else {
                    logger.debug("put failed");
                }
            }
        }).awaitListeners();

        return futurePut.isSuccess();
    }

    @Override
    public boolean delete(String key) throws IOException, InterruptedException {
        logger.debug("Overlay Delete {}", key);
        Number160 overlayKey = Number160.createHash(key);
        logger.debug("TomP2P Delete: {}", overlayKey);
        FutureRemove futureRemove = peerDHT.remove(overlayKey).start().addListener(new BaseFutureAdapter<FutureRemove>() {
            @Override
            public void operationComplete(FutureRemove future) throws Exception {
                if (future.isSuccess()) {
                    logger.debug("remove was successful");
                } else {
                    logger.debug("remove failed");
                }
            }
        }).awaitListeners();

        if (futureRemove.isCompleted()) {
            return futureRemove.isRemoved();
        } else {
            return futureRemove.isSuccess();
        }
    }

    private long calculateTTL(long creationTime, long ttl) {
        long TTL;
        long now = Instant.now().getEpochSecond();

        if (now < creationTime) {
            long diff = creationTime - now;
            long realTTL = ttl - creationTime;
            TTL = realTTL + diff;
        } else {
            TTL = ttl - creationTime;
            if (TTL < 0) {
                TTL = ttl;
            }
        }
        return TTL;
    }

    public int getPeerDHTPort() {
        return peerDHTPort;
    }

    @Scheduled(fixedRateString = "${spring.schedule.dhtBootstrap}", initialDelay = 300000)
    public void refreshBootstrap() throws InterruptedException {
        List<PeerAddress> peerAddresses = peerDHT.peer().peerBean().peerMap().all();

        for (PeerAddress peerAddress : peerAddresses) {
            if (!peers.contains(peerAddress)) {
                logger.info("Bootstrapping to new peer: {}", peerAddress.peerId());
                peerDHT.peer().bootstrap().peerAddress(peerAddress).start().await();
            }
        }
        peers = peerAddresses;
    }

    @Override
    public void close() throws InterruptedException {
        logger.info("Shutting down connection to DHT Overlay");
        peerDHT.shutdown();
//        replication.shutdown();
    }
}
