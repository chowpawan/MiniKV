package com.minikv.model;

public enum EntryType {
    PUT((byte) 0),
    DELETE((byte) 1);

    public final byte code;

    EntryType(byte code) { this.code = code; }

    public static EntryType fromCode(byte code) {
        return switch (code) {
            case 0 -> PUT;
            case 1 -> DELETE;
            default -> throw new IllegalArgumentException("Unknown entry type: " + code);
        };
    }
}
