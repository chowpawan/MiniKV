package com.minikv.server;

import com.minikv.core.LSMTree;
import java.nio.file.Path;

public class KVMain {
    public static void main(String[] args) throws Exception {
        int port = 8080;
        Path dataDir = Path.of("minikv-data");

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            if (args[i].equals("--data") && i + 1 < args.length) dataDir = Path.of(args[++i]);
        }

        LSMTree engine = new LSMTree(dataDir);
        KVAuth auth    = new KVAuth(dataDir);
        KVServer server = new KVServer(engine, auth, port);
        server.start();

        System.out.println("MiniKV Server started on port " + port);
        System.out.println("API Key: " + auth.getApiKey());
        System.out.printf("curl -H 'Authorization: Bearer %s' http://localhost:%d/get?key=hello%n",
                auth.getApiKey(), port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
            try { engine.close(); } catch (Exception ignored) {}
        }));

        Thread.currentThread().join();
    }
}
