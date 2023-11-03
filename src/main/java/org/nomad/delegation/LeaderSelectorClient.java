package org.nomad.delegation;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.framework.recipes.leader.Participant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An example leader selector client. Note that {@link LeaderSelectorListenerAdapter} which
 * has the recommended handling for connection state issues
 */
@Component
public class LeaderSelectorClient implements Closeable {
    private final Logger logger = LoggerFactory.getLogger(LeaderSelectorClient.class);
    private LeaderLatch leaderSelectorLatch;

    // create a leader selector using the given path for management
    // all participants in a given leader selection must use the same path
    // for most cases you will want your instance to requeue when it relinquishes leadership
    // leaderSelector.autoRequeue();

    public void start(CuratorFramework client, String path, String name, LeaderLatchListener listener) throws Exception {
        logger.info("Leader selection has started!");
        this.leaderSelectorLatch = new LeaderLatch(client, path, name);
        // the selection for this instance doesn't start until the leader selector is started
        // leader selection is done in the background so this call to leaderSelector.start() returns immediately
        leaderSelectorLatch.start();
        leaderSelectorLatch.addListener(listener);
    }

    @Override
    public void close() throws IOException {
        try {
            leaderSelectorLatch.close(LeaderLatch.CloseMode.NOTIFY_LEADER);
        } catch (IllegalStateException e) {
            logger.warn("Leader latch already closed");
        }

    }

    public List<Participant> getParticipants() throws Exception {
        return new ArrayList<>(leaderSelectorLatch.getParticipants());
    }

    /**
     * Returns the String UUID of the leader node
     *
     * @return id
     * @throws Exception ZK errors, interruptions, etc
     */
    public String getLeader() throws Exception {
        String leaderId = leaderSelectorLatch.getLeader().getId();
        while (leaderId.isEmpty()) {
            // busy-wait for low latency
            Thread.sleep(5);
            leaderId = leaderSelectorLatch.getLeader().getId();
        }
        return leaderId;
    }

    public boolean hasLeadership() {
        return leaderSelectorLatch.hasLeadership();
    }
}