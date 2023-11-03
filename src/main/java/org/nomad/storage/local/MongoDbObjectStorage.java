//package org.agentsmith.storage.local;
//
//import org.agentsmith.pithos.models.GameObject;
//import org.agentsmith.storage.repositories.GameObjectsRepository;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.annotation.Profile;
//import org.springframework.stereotype.Component;
//
//import java.util.NoSuchElementException;
//import java.util.Optional;
//
//@Component
//@Profile("mongo")
//@SuppressWarnings("unchecked")
//public class MongoDbObjectStorage implements LocalStorage {
//    private static final Logger logger = LoggerFactory.getLogger(MongoDbObjectStorage.class);
//
//    private final GameObjectsRepository gameObjectsRepository;
//    private boolean initialised = false;
//
//    @Autowired
//    public MongoDbObjectStorage(GameObjectsRepository gameObjectsRepository) {
//        this.gameObjectsRepository = gameObjectsRepository;
//    }
//
//    @Override
//    public void init() {
//        gameObjectsRepository.deleteAll();
//        this.initialised = true;
//    }
//
//    @Override
//    public void close() {
//        logger.info("Closing MongoDB client connection ...");
//        gameObjectsRepository.deleteAll();
//        this.initialised = false;
//    }
//
//    @Override
//    public boolean put(GameObject object) {
//        String objectId = object.getId();
//        logger.info("Adding document _id: {}, {}", objectId, object.toString());
//        if (!idExists(object.getId())) {
//            gameObjectsRepository.save(object);
//            return true;
//        }
//        logger.warn("Document with key: {} exists!", objectId);
//        return false;
//    }
//
//    @Override
//    public GameObject get(String id) throws NoSuchElementException {
//        logger.info("Getting first document matching _id: {}", id);
//        Optional<GameObject> value = gameObjectsRepository.findById(id);
//        if (value.isPresent()) {
//            return value.get();
//        } else {
//            logger.error("Get failed: {}", id);
//            throw new NoSuchElementException("Object with key: " + id + " not found!");
//        }
//    }
//
//    @Override
//    public boolean update(GameObject newValue) {
//        String objectId = newValue.getId();
//        logger.info("Updating document: _id: {} with {}", objectId, newValue.toString());
//        gameObjectsRepository.save(newValue);
//        return true;
//    }
//
//    @Override
//    public boolean delete(String id) {
//        logger.info("Deleting document _id: {}", id);
//        gameObjectsRepository.deleteById(id);
//        return true;
//    }
//
//    @Override
//    public boolean isInitialised() {
//        return initialised;
//    }
//
//    private boolean idExists(String id) {
//        Optional<GameObject> object = gameObjectsRepository.findById(id);
//        return object.isPresent();
//    }
//}
