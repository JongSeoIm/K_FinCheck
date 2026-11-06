package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class OutboxProcessRecoveryTest {
    @TempDir Path directory;

    @Test @Timeout(25) void forcedJvmExitLeavesDurableRecordAndReleasesOwnershipLock() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(java, "-cp", cp, Writer.class.getName(), directory.toString())
                .redirectErrorStream(true).start();
        try {
            var reader = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String id = CompletableFuture.supplyAsync(() -> {
                try { return reader.readLine(); } catch (Exception e) { throw new CompletionException(e); }
            }).get(10, TimeUnit.SECONDS);
            assertThat(id).matches("[0-9a-f-]{36}");
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            try (var spool = new FileQuoteOutbox(directory, 100000, 10, new ObjectMapper().findAndRegisterModules())) {
                assertThat(spool.size()).isEqualTo(1);
                assertThat(spool.first().message().eventId()).isEqualTo(id);
                assertThat(spool.first().message().quote().price()).isEqualTo(70000);
            }
        } finally { child.destroyForcibly(); }
    }

    public static class Writer {
        public static void main(String[] args) throws Exception {
            var mapper = new ObjectMapper().findAndRegisterModules();
            try (var spool = new FileQuoteOutbox(Path.of(args[0]), 100000, 10, mapper)) {
                var message = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10);
                spool.append("quotes", message);
                System.out.println(message.eventId()); System.out.flush();
                new CountDownLatch(1).await();
            }
        }
    }
}
