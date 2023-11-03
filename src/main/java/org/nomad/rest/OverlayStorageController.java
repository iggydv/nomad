package org.nomad.rest;

import io.swagger.annotations.ApiParam;
import org.nomad.api.model.GameObject;
import org.nomad.pithos.mappers.GameObjectMapperImpl;
import org.nomad.storage.overlay.DHTOverlayStorage;
import org.openapitools.api.OverlayStorageApi;
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
public class OverlayStorageController implements OverlayStorageApi {
    private final Logger logger = LoggerFactory.getLogger(OverlayStorageController.class);

    private final DHTOverlayStorage storage;

    @Autowired
    public OverlayStorageController(DHTOverlayStorage storage) {
        this.storage = storage;
    }

    @Override
    @ResponseBody
    public ResponseEntity<GameObject> getOverlayObject(@ApiParam(required = true) @PathVariable("objectId") String objectId) {
        logger.debug("Overlay Get request received!");

        org.nomad.pithos.models.GameObject internalGameObject;
        try {
            internalGameObject = storage.get(objectId);
        } catch (ClassNotFoundException | IOException | InterruptedException e) {
            logger.error("Request failed: {}", objectId);
            return ResponseEntity.badRequest().build();
        } catch (NoSuchElementException e) {
            logger.error("Object Not found id: {}", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (internalGameObject != null) {
            GameObject retrievedObject = GameObjectMapperImpl.INSTANCE.mapToAPI(internalGameObject);
            logger.debug("Get request successful!");
            return ResponseEntity.status(HttpStatus.OK).body(retrievedObject);
        } else {
            logger.error("Object Not found id: {}", objectId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * The TTL is stored as creation time + ttl
     * TTL is received as long so no need for conversion - TimeUnit.SECONDS.toSeconds()
     */
    @Override
    public ResponseEntity<Void> putOverlayObject(@Valid @RequestBody GameObject gameObject) {
        logger.debug("Overlay Put request received!");
        org.nomad.pithos.models.GameObject internalGameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObject);
        long timestamp = internalGameObject.getCreationTime();
        long TTL = timestamp + internalGameObject.getTtl();
        internalGameObject.setTtl(TTL);
        try {
            boolean result = storage.put(internalGameObject);
            if (result) {
                return ResponseEntity.ok().build();
            } else {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Unable to add GameObject {}", gameObject.toString());
            // e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }
}
