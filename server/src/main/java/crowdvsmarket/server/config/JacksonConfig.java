package crowdvsmarket.server.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Bug fix (wave-1 finding): Jackson's default {@code ACCEPT_FLOAT_AS_INT} silently truncates a
 * JSON float into an {@code int}/{@code long} field instead of rejecting it — e.g. {@code
 * {"leverage": 10.5}} bound into {@code Betting.PlaceBetRequest.leverage} (an {@code int}) became
 * {@code 10} with no error at all. Disabling it makes every integral field on every RPC/admin
 * request DTO (not just {@code leverage}) reject a non-integral JSON number with a normal 400
 * ({@code ApiExceptionMapper}'s {@code JsonProcessingException} handling), rather than silently
 * coercing it.
 */
@Singleton
public class JacksonConfig implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
