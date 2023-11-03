package org.nomad.storage.local;

import org.nomad.pithos.models.GameObject;

import java.sql.SQLException;
import java.util.NoSuchElementException;

/* Interface to allow for abstraction around the Local Storage implementation */
public interface LocalStorage {
    void init() throws SQLException;

    void close() throws SQLException;

    boolean put(GameObject value);

    GameObject get(String key) throws NoSuchElementException;

    boolean update(GameObject value) throws NoSuchElementException;

    boolean delete(String key) throws NoSuchElementException;

    boolean isInitialised();

    boolean isMalicious();

    void truncate();
}
