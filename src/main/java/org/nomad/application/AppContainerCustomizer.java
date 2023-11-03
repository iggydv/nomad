package org.nomad.application;

import org.nomad.commons.NetworkUtility;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

@Component
public class AppContainerCustomizer implements WebServerFactoryCustomizer<ConfigurableWebServerFactory> {
    private int tomcatPort;

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        int port = NetworkUtility.randomPort(4002, 4051);
        this.tomcatPort = port;
        factory.setPort(port);
    }

    public int getTomcatPort() {
        return tomcatPort;
    }
}