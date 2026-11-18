package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FileQuoteOutboxTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void reopeningRestoresOriginalIdsAndArrivalOrder() throws Exception {
        var laterTime = QuotePersistenceRepositoryTest.message(5, 70100, 120, 10);
        var earlierTime = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10);
        try (var spool = new FileQuoteOutbox(directory, 100000, 10, mapper)) {
            spool.append("quotes", laterTime); spool.append("quotes", earlierTime);
        }
        try (var spool = new FileQuoteOutbox(directory, 100000, 10, mapper)) {
            assertThat(spool.size()).isEqualTo(2);
            assertThat(spool.first().message()).isEqualTo(laterTime);
            spool.acknowledged(spool.first());
            assertThat(spool.first().message()).isEqualTo(earlierTime);
            spool.acknowledged(spool.first());
            assertThat(spool.first()).isNull();
        }
    }

    @Test void quotaRejectsNewRecordAndKeepsExistingRecord() throws Exception {
        try (var spool = new FileQuoteOutbox(directory, 100000, 1, mapper)) {
            var first = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10);
            spool.append("quotes", first);
            assertThatThrownBy(() -> spool.append("quotes", QuotePersistenceRepositoryTest.message(1, 70100, 120, 20)))
                    .isInstanceOf(IOException.class);
            assertThat(spool.first().message()).isEqualTo(first);
            assertThat(spool.size()).isEqualTo(1);
        }
    }

    @Test void byteQuotaDoesNotLeavePartialRecord() throws Exception {
        try (var spool = new FileQuoteOutbox(directory, 10, 100, mapper)) {
            assertThatThrownBy(() -> spool.append("quotes", QuotePersistenceRepositoryTest.message(0, 70000, 100, 10)))
                    .isInstanceOf(IOException.class);
            assertThat(spool.size()).isZero();
        }
        try (var paths=Files.list(directory)) { assertThat(paths.filter(p -> !p.endsWith("spool.lock")).count()).isZero(); }
    }

    @Test void secondProcessCannotOwnSameSpool() throws Exception {
        try (var spool = new FileQuoteOutbox(directory, 100000, 10, mapper)) {
            assertThatThrownBy(() -> new FileQuoteOutbox(directory, 100000, 10, mapper)).isInstanceOf(IOException.class);
        }
    }

    @Test void incompleteWriteAndCorruptRecordBlockStartupWithoutDeletion() throws Exception {
        Path incomplete = directory.resolve("00000000000000000001-test.writing");
        Files.writeString(incomplete, "{partial");
        assertThatThrownBy(() -> new FileQuoteOutbox(directory, 100000, 10, mapper)).isInstanceOf(IOException.class);
        assertThat(incomplete).exists();
        Files.delete(incomplete);
        Path corrupt = directory.resolve("00000000000000000001-00000000-0000-0000-0000-000000000001.pending");
        Files.writeString(corrupt, "{broken");
        assertThatThrownBy(() -> new FileQuoteOutbox(directory, 100000, 10, mapper)).isInstanceOf(IOException.class);
        assertThat(corrupt).exists();
    }
}
