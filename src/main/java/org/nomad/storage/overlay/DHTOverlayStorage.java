package org.nomad.storage.overlay;

import org.nomad.pithos.models.GameObject;

import java.io.IOException;

/* Interface to allow for abstraction around the DHT implementation */
public interface DHTOverlayStorage {
    int initOverlay() throws IOException;

    int joinOverlay(String dhtHostname) throws IOException, InterruptedException;

    GameObject get(String key) throws ClassNotFoundException, IOException, InterruptedException;

    GameObject getUninterruptible(String key) throws ClassNotFoundException, IOException, InterruptedException;

    boolean put(GameObject value) throws IOException, InterruptedException;

    boolean putUninterruptible(GameObject gameObject) throws IOException, InterruptedException;

    boolean update(GameObject value) throws IOException, InterruptedException;

    boolean delete(String key) throws IOException, InterruptedException;

    void close() throws InterruptedException;
}
