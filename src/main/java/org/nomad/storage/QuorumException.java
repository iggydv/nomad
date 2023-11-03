package org.nomad.storage;

public class QuorumException extends RuntimeException {
    public QuorumException() {
        super("Quorum was not reached!");
    }
}
