package org.nomad.grpc.management.services.callables;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.nomad.grpc.management.clients.PeerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;

public class HandleSuperPeerLeaveCallable implements Callable<Boolean> {
    private final Logger logger = LoggerFactory.getLogger(HandleSuperPeerLeaveCallable.class);

    final PeerClient client;

    private final RetryPolicy<Boolean> superPeerRetryPolicy = new RetryPolicy<Boolean>()
            .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()))
            .withBackoff(1, 30, ChronoUnit.SECONDS)
            .handle(Exception.class)
            .withMaxRetries(5);

    public HandleSuperPeerLeaveCallable(final PeerClient client) {
        this.client = client;
    }

    public Boolean call() throws InterruptedException {
        return Failsafe.with(superPeerRetryPolicy)
                .onFailure(e -> logger.error("failed to get group leader"))
                .onComplete(e -> logger.info("group leader saved"))
                .get(x -> client.handleSuperPeerLeaveFuture());
    }
}