package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuoteSnapshotCacheTest {
    private final StreamProperties properties = new StreamProperties();
    private final QuotePersistenceRepository repository = mock(QuotePersistenceRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final QuoteSnapshotCache cache = new QuoteSnapshotCache(redis, new ObjectMapper().findAndRegisterModules(),
            repository, properties, "test-source", "test");

    @Test void cacheMissReadsDatabaseAndTheNextRequestUsesCachedPayload() {
        var quote = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10).quote();
        when(redis.opsForValue()).thenReturn(values);
        when(repository.load("test-source", properties.getSymbols(), List.of(60)))
                .thenReturn(List.of(new QuotePersistenceRepository.Projection(quote, List.of())));

        var result = cache.read(60);
        assertThat(result.quotes().get(quote.stockCode())).isEqualTo(quote);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(values).set(anyString(), payload.capture(), eq(Duration.ofSeconds(1)));
        when(values.get(anyString())).thenReturn(payload.getValue());

        assertThat(cache.read(60)).isEqualTo(result);
        verify(repository, times(1)).load("test-source", properties.getSymbols(), List.of(60));
    }

    @Test void redisOutageStillReturnsDatabaseDataAndSuppressesImmediateRetry() {
        var quote = QuotePersistenceRepositoryTest.message(0, 70000, 100, 10).quote();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        when(repository.load("test-source", properties.getSymbols(), List.of(60)))
                .thenReturn(List.of(new QuotePersistenceRepository.Projection(quote, List.of())));

        assertThat(cache.read(60).quotes().get(quote.stockCode())).isEqualTo(quote);
        assertThat(cache.read(60).quotes().get(quote.stockCode())).isEqualTo(quote);
        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
