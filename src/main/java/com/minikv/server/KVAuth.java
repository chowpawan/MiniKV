package com.minikv.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

public class KVAuth {
    private static final int KEY_BYTES = 32;
    private final Path keyFile;
    private volatile String currentKey;

    public KVAuth(Path dataDir) throws IOException {
        this.keyFile = dataDir.resolve("auth.key");
        this.currentKey = loadOrGenerate();
    }

    public boolean isValid(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        return constantTimeEquals(authHeader.substring("Bearer ".length()).strip(), currentKey);
    }

    public synchronized String rotate() throws IOException {
        String newKey = generate();
        Files.writeString(keyFile, newKey);
        currentKey = newKey;
        return newKey;
    }

    public String getApiKey() { return currentKey; }

    private String loadOrGenerate() throws IOException {
        if (Files.exists(keyFile)) return Files.readString(keyFile).strip();
        String key = generate();
        Files.writeString(keyFile, key);
        return key;
    }

    private static String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(KEY_BYTES * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }
}
