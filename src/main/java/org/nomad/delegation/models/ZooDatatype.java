package org.nomad.delegation.models;

public enum ZooDatatype {
    GROUP("group"),
    PEER("peer"),
    DHT("dht");

    String value;

    ZooDatatype(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
