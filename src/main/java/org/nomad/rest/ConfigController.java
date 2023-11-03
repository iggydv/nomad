package org.nomad.rest;

import io.swagger.annotations.ApiParam;
import org.nomad.api.model.RetrievalMode;
import org.nomad.api.model.StorageMode;
import org.nomad.config.Config;
import org.nomad.storage.PeerStorage;
import org.openapitools.api.ConfigApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@RestController
public class ConfigController implements ConfigApi {
    private final Logger logger = LoggerFactory.getLogger(ConfigController.class);

    private final Config config;
    private final PeerStorage peerStorage;

    @Autowired
    public ConfigController(PeerStorage storage, Config config) {
        this.peerStorage = storage;
        this.config = config;
    }

    @Override
    @ResponseBody
    public ResponseEntity<Void> setStorageMode(@NotNull @ApiParam(value = "Storage mode used for _put_.", required = true, allowableValues = "FAST, SAFE") @Valid @RequestParam(value = "storageMode", required = true) StorageMode storageMode) {
        logger.info("Update Storage-Mode request received!");
        String mode = storageMode.getValue().toLowerCase();
        config.getStorage().setStorageMode(mode);
        peerStorage.setStorageMode(mode);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @Override
    @ResponseBody
    public ResponseEntity<Void> setRetrievalMode(@NotNull @ApiParam(value = "Storage mode used for _get_.", required = true, allowableValues = "FAST, SAFE, PARALLEL") @Valid @RequestParam(value = "retrievalMode", required = true) RetrievalMode retrievalMode) {
        logger.info("Update Retrieval-Mode request received!");
        String mode = retrievalMode.getValue().toLowerCase();
        config.getStorage().setRetrievalMode(mode);
        peerStorage.setRetrievalMode(mode);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @Override
    @ResponseBody
    public ResponseEntity<Void> migrationEnable(@NotNull @ApiParam(value = "true = enable, false = disable", required = true) @Valid @RequestParam(value = "enabled") Boolean enabled) {
        logger.info("Update migration mode request received!");
        config.getGroup().setMigration(enabled);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @Override
    @ResponseBody
    public ResponseEntity<Void> maliciousPeerEnable(@NotNull @ApiParam(value = "true = enable, false = disable", required = true) @Valid @RequestParam(value = "enabled") Boolean enabled) {
        logger.info("Evil mode activated!");
        config.setMalicious(enabled);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

    @Override
    @ResponseBody
    public ResponseEntity<Void> updateReplicationFactor(@NotNull @ApiParam(value = "set replication factor", required = true) @Valid @RequestParam(value = "rf", required = true) BigDecimal rf) {
        logger.info("Updating RF: {}", rf);
        config.getStorage().setReplicationFactor(rf.intValue());
        peerStorage.setRF();
        return ResponseEntity.status(HttpStatus.OK).build();
    }
}
