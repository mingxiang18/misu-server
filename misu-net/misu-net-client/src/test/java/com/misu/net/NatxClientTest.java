package com.misu.net;

import com.misu.net.config.NatxConfigEntity;

/**
 *
 */
public class NatxClientTest {

    public static void main(String[] args) throws Exception {

        String serverAddress = "localhost";
        int serverPort = 30100;
        String secret = "secret";
        String proxyAddress = "localhost";
        int proxyPort = 30200;
        int remotePort = 30101;
        NatxConfigEntity natxConfigEntity = new NatxConfigEntity();
        natxConfigEntity.setServerAddress(serverAddress);
        natxConfigEntity.setServerPort(serverPort);
        natxConfigEntity.setServerSecret(secret);
        natxConfigEntity.setProxyAddress(proxyAddress);
        natxConfigEntity.setProxyPort(proxyPort);
        natxConfigEntity.setRemotePort(remotePort);


        NatxClient client = new NatxClient();
        for (int i = 0; i < 20; i++) {
            client.connect(natxConfigEntity);
        }
    }
}
