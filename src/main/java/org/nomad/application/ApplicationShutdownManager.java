package org.nomad.application;

import org.nomad.pithos.MainController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class ApplicationShutdownManager implements ApplicationListener<ContextClosedEvent> {

    Logger logger = LoggerFactory.getLogger(ApplicationShutdownManager.class);

    @Autowired
    MainController mainController;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        logger.info("Nomad System Shutdown");
    }
}
