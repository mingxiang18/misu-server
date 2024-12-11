package com.misu.net;

import org.apache.commons.cli.*;

/**
 *
 */
public class NatxServerTest {

    public static void main(String[] args) throws ParseException, InterruptedException {

        int port = 30100;
        String secret = "secret";
        NatxServer server = new NatxServer();
        server.start(port, secret);

        System.out.println("Natx server started on port " + port);
    }
}
