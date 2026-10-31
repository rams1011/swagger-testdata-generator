package com.tx.api.test.generator.studio.config;

import com.tx.api.test.generator.studio.cache.StudioRedisCache;
import com.tx.api.test.generator.studio.storage.JdbcStudioStorage;
import com.tx.api.test.generator.studio.storage.RedisCachingStudioStorage;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@ConditionalOnProperty(prefix = "testdata.cache.redis", name = "enabled", havingValue = "true")
public class RedisStudioCacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisStudioCacheConfiguration.class);

    /**
     * When Redis is enabled but unreachable, fall back to JDBC storage so the API still starts.
     */
    @Bean
    @Primary
    StudioStorage studioStorage(JdbcStudioStorage jdbcStudioStorage, StudioProperties properties) {
        StudioProperties.Redis redis = properties.getCache().getRedis();
        LettuceConnectionFactory factory = null;
        try {
            factory = createConnectionFactory(redis);
            factory.afterPropertiesSet();
            verifyConnection(factory);
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            StudioRedisCache cache = new StudioRedisCache(template, properties);
            log.info(
                    "Studio Redis cache ENABLED (host={} port={} ssl={}). Reads may be served from Redis; see source=Redis/DB logs.",
                    redis.getHost(),
                    redis.getPort(),
                    redis.isSsl());
            return new RedisCachingStudioStorage(jdbcStudioStorage, cache);
        } catch (Exception error) {
            if (factory != null) {
                try {
                    factory.destroy();
                } catch (Exception suppressed) {
                    log.debug("Could not destroy Redis connection factory after failed probe: {}", suppressed.toString());
                }
            }
            log.error(
                    "Studio Redis connection failed (host={} port={}): {}. "
                            + "Redis cache is DISABLED for this process; all reads will use the database.",
                    redis.getHost(),
                    redis.getPort(),
                    error.getMessage());
            log.warn(
                    "Studio started without Redis cache. Fix connectivity or set TESTDATA_REDIS_CACHE_ENABLED=false to silence this path.");
            return jdbcStudioStorage;
        }
    }

    private static void verifyConnection(LettuceConnectionFactory factory) {
        RedisConnection connection = factory.getConnection();
        try {
            String pong = connection.ping();
            if (pong == null) {
                throw new IllegalStateException("Redis PING returned no response");
            }
        } finally {
            connection.close();
        }
    }

    private static LettuceConnectionFactory createConnectionFactory(StudioProperties.Redis redis) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
        if (StringUtils.isNotBlank(redis.getPassword())) {
            standalone.setPassword(RedisPassword.of(redis.getPassword()));
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder();
        if (redis.isSsl()) {
            LettuceClientConfiguration.LettuceSslClientConfigurationBuilder ssl = builder.useSsl();
            if (redis.isDisablePeerVerification()) {
                ssl.disablePeerVerification();
                log.info("Studio Redis TLS enabled with peer verification disabled (host={})", redis.getHost());
            } else {
                log.info("Studio Redis TLS enabled with default CA verification (host={})", redis.getHost());
            }
        }
        return new LettuceConnectionFactory(standalone, builder.build());
    }
}
