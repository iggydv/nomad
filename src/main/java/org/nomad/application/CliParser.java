package org.nomad.application;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class CliParser {

    private static Options createCliOptions() {
        Options options = new Options();

        Option mode = new Option("m", "mode", true, "storage mode");
        mode.setRequired(false);
        Option help = new Option("h", "help", false, "print this message");
        help.setRequired(false);
        Option dht = new Option("dht", "overlay", true, "overlay storage implementation");
        dht.setRequired(false);
        Option type = new Option("t", "type", true, "node type");
        dht.setRequired(false);

        options.addOption(mode);
        options.addOption(help);
        options.addOption(dht);
        options.addOption(type);
        return options;
    }

    public static Object2ObjectOpenHashMap<NomadOption, String> parseCliArguments(String[] args) throws ParseException {
        HelpFormatter helpFormatter = new HelpFormatter();
        Options cliOptions = createCliOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;

        Object2ObjectOpenHashMap<NomadOption, String> parsedResults = new Object2ObjectOpenHashMap<>();

        try {
            cmd = parser.parse(cliOptions, args);
            if (cmd.hasOption("help")) {
                helpFormatter.printHelp("ant", cliOptions);
            }
            if (cmd.hasOption("mode")) {
                switch (cmd.getOptionValue("mode")) {
                    case "mongo":
                        parsedResults.put(NomadOption.MODE, "mongo");
                        break;
                    case "rocksdb":
                        parsedResults.put(NomadOption.MODE, "rocksdb");
                        break;
                    case "h2":
                    default:
                        parsedResults.put(NomadOption.MODE, "h2");
                }
            } else {
                parsedResults.put(NomadOption.MODE, "h2");
            }

            if (cmd.hasOption("dht")) {
                switch (cmd.getOptionValue("dht")) {
                    case "tomp2p":
                        parsedResults.put(NomadOption.DHT, "tomp2p");
                        break;
                    case "chord":
                    default:
                        parsedResults.put(NomadOption.DHT, "chord");
                }
            } else {
                parsedResults.put(NomadOption.DHT, "tomp2p");
            }

            if (cmd.hasOption("t") || (cmd.hasOption("type"))) {
                switch (cmd.getOptionValue("type")) {
                    case "super-peer":
                        parsedResults.put(NomadOption.TYPE, "sp");
                        break;
                    case "peer":
                    default:
                        parsedResults.put(NomadOption.TYPE, "p");
                }
            } else {
                parsedResults.put(NomadOption.TYPE, "p");
            }

        } catch (ParseException e) {
            helpFormatter.printHelp("ant", cliOptions);
            System.exit(1);
        }
        return parsedResults;
    }

    public enum NomadOption {
        MODE,
        DHT,
        TYPE
    }
}
