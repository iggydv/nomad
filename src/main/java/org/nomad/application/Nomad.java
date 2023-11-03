package org.nomad.application;

import io.swagger.annotations.SwaggerDefinition;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.commons.cli.ParseException;
import org.nomad.pithos.MainController;
import org.nomad.pithos.models.ComponentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.nomad.application.CliParser.NomadOption.DHT;
import static org.nomad.application.CliParser.NomadOption.MODE;
import static org.nomad.application.CliParser.NomadOption.TYPE;

@SwaggerDefinition
@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
@EnableWebMvc
@ComponentScan("org.nomad.*")
public class Nomad extends SpringBootServletInitializer {
    private static final Logger logger = LoggerFactory.getLogger(Nomad.class);
    private static String mode;
    private static String dht;
    private static String type;
    private final MainController mainController;
    @Autowired
    private ApplicationShutdownManager shutdownManager;

    @Autowired
    public Nomad(MainController mainController) {
        this.mainController = mainController;
    }

    public static void main(String... args) throws ParseException {
        Object2ObjectOpenHashMap<CliParser.NomadOption, String> cliArgs = CliParser.parseCliArguments(args);

        mode = cliArgs.get(MODE);
        dht = cliArgs.get(DHT);
        type = cliArgs.get(TYPE);

        System.setProperty("spring.profiles.active", "dev," + dht + "," + mode);
        SpringApplication app = new SpringApplication(Nomad.class);
        app.run(args);
    }

    /**
     * Used for evaluation purposes only
     */
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            if (type.equals("p")) {
                mainController.start(ComponentType.PEER);
            } else if (type.equals("sp")) {
                mainController.start(ComponentType.SUPER_PEER);
            }
        } catch (Exception e) {
            logger.error("Main controller failed, Force exit: {}", e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        }
    }
}
