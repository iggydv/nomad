package org.nomad.rest;

import io.swagger.annotations.ApiParam;
import org.nomad.api.model.GameObject;
import org.nomad.config.Config;
import org.nomad.pithos.mappers.GameObjectMapperImpl;
import org.nomad.storage.QuorumException;
import org.nomad.storage.group.GroupStorage;
import org.openapitools.api.GroupStorageApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.io.IOException;
import java.util.NoSuchElementException;

@RestController
public class GroupStorageController implements GroupStorageApi {
    private final Logger logger = LoggerFactory.getLogger(GroupStorageController.class);

    private final GroupStorage storage;
    private final String RETRIEVAL_MODE;
    private final String STORAGE_MODE;

    @Autowired
    public GroupStorageController(GroupStorage storage, Config config) {
        this.storage = storage;
        this.RETRIEVAL_MODE = config.getStorage().getRetrievalMode();
        this.STORAGE_MODE = config.getStorage().getStorageMode();
    }

    @Override
    @ResponseBody
    public ResponseEntity<GameObject> getGroupObjectFast(@ApiParam(required = true) @PathVariable("objectId") String objectId) {
        logger.debug("Group Get request received!");
        if (!storage.isInitialized()) {
            logger.warn("Group storage is not initialized");
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }

        org.nomad.pithos.models.GameObject internalGameObject = null;

        try {
            internalGameObject = storage.fastGet(objectId);
        } catch (NoSuchElementException e) {
            logger.error("Object {} not found!", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception ex) {
            logger.error("Unable to get Object with id: {}", objectId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }


        if (internalGameObject != null) {
            GameObject retrievedObject = GameObjectMapperImpl.INSTANCE.mapToAPI(internalGameObject);
            logger.debug("Get request successful!");
            return ResponseEntity.status(HttpStatus.OK).body(retrievedObject);
        } else {
            logger.error("Unable to get Object with id: {}", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Override
    @ResponseBody
    public ResponseEntity<GameObject> getGroupObjectParallel(@ApiParam(required = true) @PathVariable("objectId") String objectId) {
        logger.debug("Group Get request received!");
        if (!storage.isInitialized()) {
            logger.warn("Group storage is not initialized");
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }

        org.nomad.pithos.models.GameObject internalGameObject = null;

        try {
            internalGameObject = storage.parallelGet(objectId);
        } catch (NoSuchElementException e) {
            logger.error("Object {} not found!", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception ex) {
            logger.error("Unable to get Object with id: {}", objectId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }


        if (internalGameObject != null) {
            GameObject retrievedObject = GameObjectMapperImpl.INSTANCE.mapToAPI(internalGameObject);
            logger.debug("Get request successful!");
            return ResponseEntity.status(HttpStatus.OK).body(retrievedObject);
        } else {
            logger.error("Unable to get Object with id: {}", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Override
    @ResponseBody
    public ResponseEntity<GameObject> getGroupObjectSafe(@ApiParam(required = true) @PathVariable("objectId") String objectId) {
        logger.debug("Group Get request received!");
        if (!storage.isInitialized()) {
            logger.warn("Group storage is not initialized");
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
        }

        org.nomad.pithos.models.GameObject internalGameObject;

        try {
            internalGameObject = storage.safeGet(objectId);
        } catch (NoSuchElementException e) {
            logger.error("Object {} not found!", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (QuorumException | IOException ex) {
            logger.error("Unable to get Object with id: {}", objectId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (internalGameObject != null) {
            GameObject retrievedObject = GameObjectMapperImpl.INSTANCE.mapToAPI(internalGameObject);
            logger.debug("Get request successful!");
            return ResponseEntity.status(HttpStatus.OK).body(retrievedObject);
        } else {
            logger.error("Unable to get Object with id: {}", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Override
    public ResponseEntity<Void> putGroupObjectFast(@Valid @RequestBody GameObject gameObject) {
        logger.debug("Group Put request received!");
        org.nomad.pithos.models.GameObject internalGameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObject);
        long timestamp = internalGameObject.getCreationTime();
        long TTL = timestamp + internalGameObject.getTtl();
        // Set the adjusted TTL
        internalGameObject.setTtl(TTL);
        try {
            boolean result = storage.fastPut(internalGameObject);
            if (result) {
                return ResponseEntity.ok().build();
            } else {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        } catch (IOException e) {
            logger.error("Unable to add GameObject {}", gameObject.toString());
            // e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    @Override
    public ResponseEntity<Void> putGroupObjectSafe(@Valid @RequestBody GameObject gameObject) {
        logger.debug("Group Put request received!");
        org.nomad.pithos.models.GameObject internalGameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObject);
        long timestamp = internalGameObject.getCreationTime();
        long TTL = timestamp + internalGameObject.getTtl();
        // Set the adjusted TTL
        internalGameObject.setTtl(TTL);
        try {
            boolean result = storage.safePut(internalGameObject);
            if (result) {
                return ResponseEntity.ok().build();
            } else {
                return ResponseEntity.badRequest().build();
            }
        } catch (IllegalStateException e) {
            logger.error("Unable to add GameObject {}", gameObject.toString());
            // e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }
}
