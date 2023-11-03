package org.nomad.storage.overlay;

import com.chord.data.URL;
import com.chord.service.Chord;
import com.chord.service.PropertiesLoader;
import com.chord.service.ServiceException;
import com.chord.service.impl.ChordImpl;
import eclipse.StringKey;
import org.apache.http.annotation.Experimental;
import org.nomad.commons.NetworkUtility;
import org.nomad.pithos.models.GameObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import javax.el.MethodNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.NoSuchElementException;
import java.util.Set;

@Repository
@Profile("chord")
@Experimental
public class OpenChord implements DHTOverlayStorage {
    private final Logger logger = LoggerFactory.getLogger(TomP2POverlayStorage.class);
    private final int bootstrapPort = 4001;
    private final String bootstrapHostName;
    private final String protocol;
    private int peerDHTPort;
    private URL localURL;
    private Chord chord;

    @Autowired
    public OpenChord() {
        PropertiesLoader.loadPropertyFile();
        this.protocol = URL.KNOWN_PROTOCOLS.get(URL.SOCKET_PROTOCOL);
        this.bootstrapHostName = NetworkUtility.getIP();

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
    @Experimental
    public int initOverlay() throws IOException {
        peerDHTPort = bootstrapPort;

        if (!NetworkUtility.available(peerDHTPort)) {
            logger.info("Assigning new port for Overlay...");
            peerDHTPort = NetworkUtility.randomPort(5001, 8999);
        }
        logger.debug("Creating TomP2P peer, with Port {}", peerDHTPort);

        try {
            localURL = new URL(protocol + "://" + bootstrapHostName + ":" + peerDHTPort + "/");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        chord = new ChordImpl();
        try {
            chord.create(localURL);
        } catch (ServiceException e) {
            throw new RuntimeException("Could not create DHT!", e);
        }
        return peerDHTPort;
    }

    @Override
    @Experimental
    public int joinOverlay(String dhtHostname) throws IOException, InterruptedException {
        logger.debug("Initializing Overlay storage with dht Peer hostname: {}", dhtHostname);
        peerDHTPort = bootstrapPort;

        if (!NetworkUtility.available(peerDHTPort)) {
            logger.warn("Assigning new port for Overlay...");
            peerDHTPort = NetworkUtility.randomPort(5001, 8999);
        }

        try {
            localURL = new URL(protocol + "://" + bootstrapHostName + ":" + peerDHTPort + "/");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        chord = new ChordImpl();
        URL bootstrapURL;
        try {
            bootstrapURL = new URL(protocol + "://" + dhtHostname + "/");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        try {
            chord.join(localURL, bootstrapURL);
        } catch (ServiceException e) {
            throw new RuntimeException("Could not join DHT!", e);
        }
        return peerDHTPort;
    }

    @Override
    @Experimental
    public GameObject get(String key) throws ClassNotFoundException, IOException, InterruptedException {
        try {
            Set<Serializable> resultObject = chord.retrieve(new StringKey(key));
            if (resultObject != null) {
                return (GameObject) resultObject.toArray()[0];
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        throw new NoSuchElementException();
    }

    @Override
    @Experimental
    public GameObject getUninterruptible(String key) throws ClassNotFoundException, IOException, InterruptedException {
        return get(key);
    }

    @Override
    @Experimental
    public boolean put(GameObject gameObject) throws IOException, InterruptedException {
        String id = gameObject.getId();
        logger.debug("Overlay Put {}", id);

        StringKey myKey = new StringKey(id);
        try {
            chord.insert(myKey, gameObject);
            return true;
        } catch (ServiceException e) {
            logger.error("Unable to put object");
        }
        return false;
    }

    @Override
    @Experimental
    public boolean putUninterruptible(GameObject gameObject) throws IOException, InterruptedException {
        return put(gameObject);
    }

    @Override
    @Experimental
    public boolean update(GameObject value) throws IOException, InterruptedException {
        throw new MethodNotFoundException();
    }

    @Override
    @Experimental
    public boolean delete(String key) throws IOException, InterruptedException {
        throw new MethodNotFoundException();
    }

    @Override
    @Experimental
    public void close() throws InterruptedException {
        try {
            chord.leave();
        } catch (ServiceException e) {
            logger.error("Error occurred whilst closing chord connection");
            e.printStackTrace();
        }
    }
}
