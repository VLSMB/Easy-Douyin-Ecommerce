package com.example.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.Cache;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MybatisRedisCache implements Cache {

    private final String id;
    private static RedisTemplate<String, Object> redisTemplate;
    private static final int expireHour = 4;

    public static void setRedisTemplate(RedisTemplate<String, Object> redisTemplate) {
        MybatisRedisCache.redisTemplate = redisTemplate;
    }

    public MybatisRedisCache(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void putObject(Object key, Object value) {
        redisTemplate.opsForValue().set(generateKey(key), value, expireHour, TimeUnit.HOURS);
    }

    @Override
    public Object getObject(Object key) {
        return redisTemplate.opsForValue().get(generateKey(key));
    }

    @Override
    public Object removeObject(Object key) {
        return redisTemplate.delete(generateKey(key));
    }

    @Override
    public void clear() {
        Objects.requireNonNull(redisTemplate.getConnectionFactory()).getConnection().flushDb();
    }

    @Override
    public int getSize() {
        return Objects.requireNonNull(redisTemplate.execute(RedisServerCommands::dbSize)).intValue();
    }

    private String generateKey(Object key) {
        return String.format("mybatis:cache:%s:%s", id, key);
    }
}
