package org.nomad.pithos.components;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.config.Config;
import org.nomad.delegation.DirectoryServerClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@ContextConfiguration(classes = {DirectoryServerClient.class, Config.class})
@ExtendWith(MockitoExtension.class)
class SuperPeerTest {

    @Mock
    DirectoryServerClient directoryServerClient;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    Config config;

    @InjectMocks
    SuperPeer superPeer;

    @Test
    void pickNRandom() {

        ArrayList<String> reference = new ObjectArrayList<>(Arrays.asList("1", "2", "3"));

        ArrayList<String> result = superPeer.pickNRandom(reference, 3);
        assertTrue(result.containsAll(reference));

        ArrayList<String> result2 = superPeer.pickNRandom(reference, 2);
        assertTrue(result2.size() == 2);

        ArrayList<String> result3 = superPeer.pickNRandom(reference, 1);
        assertTrue(result3.size() == 1);

        ArrayList<String> result4 = superPeer.pickNRandom(reference, 0);
        assertTrue(result4.size() == 0);
    }
}