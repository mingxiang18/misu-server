package com.misu.net;

import org.apache.commons.cli.*;

/**
 *
 */
public class NatxServerStarter {

    public static void main(String[] args) throws ParseException, InterruptedException {

        Options options = new Options();
        options.addOption("port", true, "Natx server port");
        options.addOption("secret", true, "Natx server secret");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        int port = Integer.parseInt(cmd.getOptionValue("port", "30100"));
        String secret = cmd.getOptionValue("secret", "secret");
        NatxServer server = new NatxServer();
        server.start(port, secret);

        System.out.println("Natx server started on port " + port);
    }
}
