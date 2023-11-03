package org.nomad.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.ApiParam;
import org.nomad.api.model.GameObject;
import org.nomad.pithos.mappers.GameObjectMapperImpl;
import org.nomad.pithos.models.MetaData;
import org.nomad.storage.PeerStorage;
import org.openapitools.api.StorageApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
public class PeerStorageController implements StorageApi {
    private final Logger logger = LoggerFactory.getLogger(PeerStorageController.class);

    @Resource(name = "peerStorage")
    private final PeerStorage peerStorage;

    @Autowired
    public PeerStorageController(PeerStorage peerStorage) {
        this.peerStorage = peerStorage;
    }

    @Override
    @ResponseBody
    public ResponseEntity<GameObject> getObject(@ApiParam(required = true) @PathVariable("objectId") String objectId) {
        logger.debug("Get request received!");
        org.nomad.pithos.models.GameObject internalGameObject;

        if (!peerStorage.isInitialized()) {
            logger.debug("Peer storage is not initialized!");
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
        }

        try {
            internalGameObject = peerStorage.get(objectId, true, true);

            if (internalGameObject != null) {
                GameObject retrievedObject = GameObjectMapperImpl.INSTANCE.mapToAPI(internalGameObject);
                logger.debug("Get request successful!");
                if (retrievedObject.getId().equals("ohno")) {
                    return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).body(retrievedObject);
                }
                return ResponseEntity.status(HttpStatus.OK).body(retrievedObject);
            } else {
                logger.error("Object {} not found!", objectId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        } catch (NoSuchElementException | InterruptedException e) {
            logger.error("Object {} not found!", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * The TTL is stored as creation time + ttl
     * TTL is received as long so no need for conversion - TimeUnit.SECONDS.toSeconds()
     */
    @Override
    public ResponseEntity<Void> putObject(@Valid @RequestBody GameObject gameObject) {
        logger.debug("Put request received!");
        org.nomad.pithos.models.GameObject internalGameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObject);
        long timestamp = internalGameObject.getCreationTime();
        long TTL = timestamp + internalGameObject.getTtl();
        // Set the adjusted TTL
        internalGameObject.setTtl(TTL);
        boolean result;
        try {
            result = peerStorage.put(internalGameObject, true, true);
            logger.debug("Put request successful!");
            return result ? ResponseEntity.status(HttpStatus.CREATED).build() : ResponseEntity.badRequest().build();
        } catch (InterruptedException e) {
            logger.error("Interrupted whilst adding GameObject {}", gameObject.getId());
            // e.printStackTrace();
            return ResponseEntity.badRequest().build();
        } catch (DuplicateKeyException e) {
            logger.error("Object already exists: {}", gameObject.getId());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IOException e) {
            logger.error("Error while adding GameObject {}", gameObject.getId());
            // e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * The TTL is stored as creation time + ttl
     * TTL is received as long so no need for conversion - TimeUnit.SECONDS.toSeconds()
     */
    @Override
    public ResponseEntity<Void> updateObject(@Valid @RequestBody GameObject gameObject) {
        logger.debug("Update request received!");
        org.nomad.pithos.models.GameObject internalGameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObject);
        long timestamp = internalGameObject.getCreationTime();
        long TTL = timestamp + internalGameObject.getTtl();
        // Set the adjusted TTL
        internalGameObject.setTtl(TTL);
        try {
            peerStorage.update(internalGameObject);
            logger.debug("Update request successful!");
            return ResponseEntity.ok().build();
        } catch (InterruptedException e) {
            logger.error("Unable to update GameObject: {}", gameObject.getId());
            Thread.currentThread().interrupt();
            return ResponseEntity.badRequest().build();
        }
    }

    @Override
    public ResponseEntity<Void> deleteObject(@ApiParam(required = true) @PathVariable("objectId") String objectId) {
        logger.debug("Delete request received!");
        try {
            peerStorage.delete(objectId);
            logger.debug("Delete request successful!");
            return ResponseEntity.ok().build();
        } catch (InterruptedException e) {
            logger.error("Unable to delete object with ID: {}", objectId);
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Override
    public ResponseEntity<String> getPeerLedger() {
        logger.debug("Get Peer-Ledger request received!");
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Collection<MetaData>> peerLedger = peerStorage.getGroupLedger().getPeerLedgerAsMap();
            logger.debug("Get Peer-Ledger request successful!");
            return ResponseEntity.ok().body(mapper.writeValueAsString(peerLedger));
        } catch (Exception e) {
            logger.error("Peer-Ledger does not exist!");
            // e.printStackTrace();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Override
    public ResponseEntity<String> getObjectLedger() {
        logger.debug("Get Object-Ledger request received!");
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Collection<MetaData>> objectLedger = peerStorage.getGroupLedger().getObjectLedgerAsMap();
            logger.debug("Get Object-Ledger request successful!");
            return ResponseEntity.ok().body(mapper.writeValueAsString(objectLedger.toString()));
        } catch (Exception e) {
            logger.error("Object-Ledger does not exist!");
            // e.printStackTrace();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
