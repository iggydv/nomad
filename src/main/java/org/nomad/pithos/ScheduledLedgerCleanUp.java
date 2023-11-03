package org.nomad.pithos;

import com.google.common.util.concurrent.AbstractScheduledService;
import org.nomad.pithos.components.GroupLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class ScheduledLedgerCleanUp extends AbstractScheduledService {
    private final Logger logger = LoggerFactory.getLogger(ScheduledLedgerCleanUp.class);
    private final GroupLedger ledger = GroupLedger.getInstance();

    @Override
    protected void runOneIteration() throws Exception {
        boolean result = ledger.cleanExpiredObjects();
        if (result) {
            logger.info("Ledger cleanup was successful");
        } else {
            logger.debug("No Ledger entries cleaned up");
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedRateSchedule(60, 60, TimeUnit.SECONDS);
    }
}
