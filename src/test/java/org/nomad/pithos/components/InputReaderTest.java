package org.nomad.pithos.components;

import org.junit.jupiter.api.Test;

import java.io.IOException;

class InputReaderTest {

    InputReader subject = new InputReader();

    @Test
    void readMovements() throws IOException {
        subject.readMovements(0);
    }
}