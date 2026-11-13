package com.kfincheck.realtime;

/** Local development only: collects the policy briefing backfill and exits when it is complete. */
public class PolicyBackfillApplication {
    public static void main(String[] args) {
        LocalRealtimeApplication.main(new String[] {
                "--stream.persistence.enabled=false",
                "--stream.relay-enabled=false",
                "--stream.outbox.enabled=false",
                "--policy.collection.exit-when-backfill-complete=true"
        });
    }
}
