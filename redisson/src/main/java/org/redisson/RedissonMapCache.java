/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.api.MapOptions;
import org.redisson.api.RFuture;
import org.redisson.api.RMapCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.api.map.event.EntryCreatedListener;
import org.redisson.api.map.event.EntryEvent;
import org.redisson.api.map.event.EntryExpiredListener;
import org.redisson.api.map.event.EntryRemovedListener;
import org.redisson.api.map.event.EntryUpdatedListener;
import org.redisson.api.map.event.MapEntryListener;
import org.redisson.client.RedisClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.MapScanCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommand.ValueType;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.convertor.NumberConvertor;
import org.redisson.client.protocol.decoder.ListMultiDecoder;
import org.redisson.client.protocol.decoder.LongMultiDecoder;
import org.redisson.client.protocol.decoder.MapCacheScanResult;
import org.redisson.client.protocol.decoder.MapCacheScanResultReplayDecoder;
import org.redisson.client.protocol.decoder.MapScanResult;
import org.redisson.client.protocol.decoder.ObjectListDecoder;
import org.redisson.client.protocol.decoder.ObjectMapDecoder;
import org.redisson.client.protocol.decoder.ScanObjectEntry;
import org.redisson.codec.MapCacheEventCodec;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.connection.decoder.MapGetAllDecoder;
import org.redisson.eviction.EvictionScheduler;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * <p>Map-based cache with ability to set TTL for each entry via
 * {@link #put(Object, Object, long, TimeUnit)} or {@link #putIfAbsent(Object, Object, long, TimeUnit)} methods.
 * And therefore has an complex lua-scripts inside.</p>
 *
 * <p>Current redis implementation doesnt have map entry eviction functionality.
 * Thus entries are checked for TTL expiration during any key/value/entry read operation.
 * If key/value/entry expired then it doesn't returns and clean task runs asynchronous.
 * Clean task deletes removes 100 expired entries at once.
 * In addition there is {@link org.redisson.eviction.EvictionScheduler}. This scheduler
 * deletes expired entries in time interval between 5 seconds to 2 hours.</p>
 *
 * <p>If eviction is not required then it's better to use {@link org.redisson.RedissonMap} object.</p>
 *
 * @author Nikita Koksharov
 *
 * @param <K> key
 * @param <V> value
 */
public class RedissonMapCache<K, V> extends RedissonMap<K, V> implements RMapCache<K, V> {

    public RedissonMapCache(EvictionScheduler evictionScheduler, CommandAsyncExecutor commandExecutor,
                            String name, RedissonClient redisson, MapOptions<K, V> options) {
        super(commandExecutor, name, redisson, options);
        if (evictionScheduler != null) {
            evictionScheduler.schedule(getName(), getTimeoutSetName(), getIdleSetName(), getExpiredChannelName(), getLastAccessTimeSetName());
        }
    }

    public RedissonMapCache(Codec codec, EvictionScheduler evictionScheduler, CommandAsyncExecutor commandExecutor,
                            String name, RedissonClient redisson, MapOptions<K, V> options) {
        super(codec, commandExecutor, name, redisson, options);
        if (evictionScheduler != null) {
            evictionScheduler.schedule(getName(), getTimeoutSetName(), getIdleSetName(), getExpiredChannelName(), getLastAccessTimeSetName());
        }
    }

    @Override
    public boolean trySetMaxSize(int maxSize) {
        return get(trySetMaxSizeAsync(maxSize));
    }
    
    @Override
    public RFuture<Boolean> trySetMaxSizeAsync(int maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize should be greater than zero");
        }
        
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.HSETNX, getOptionsName(), "max-size", maxSize);
    }
    
    @Override
    public void setMaxSize(int maxSize) {
        get(setMaxSizeAsync(maxSize));
    }
    
    @Override
    public RFuture<Void> setMaxSizeAsync(int maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize should be greater than zero");
        }
        
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.HSET_VOID, getOptionsName(), "max-size", maxSize);
    }

    
    @Override
    public RFuture<Boolean> containsKeyAsync(Object key) {
        checkKey(key);

        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_BOOLEAN,
        "local value = redis.call('hget', KEYS[1], ARGV[2]); " +
                "local expireDate = 92233720368547758; " +
                "if value ~= false then " +
                "" +
                "    local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); " +
                "    if maxSize ~= nil and maxSize ~= 0 then " +
                "        redis.call('zadd', KEYS[4], tonumber(ARGV[1]), ARGV[2]); " +
                "    end;" +
                "" +
                "    local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); " +
                "    if expireDateScore ~= false then " +
                "        expireDate = tonumber(expireDateScore) " +
                "    end; " +
                "    local t, val = struct.unpack('dLc0', value); " +
                "    if t ~= 0 then " +
                "        local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); " +
                "        if expireIdle ~= false then " +
                "            if tonumber(expireIdle) > tonumber(ARGV[1]) then " +
                "                local value = struct.pack('dLc0', t, string.len(val), val); " +
                "                redis.call('hset', KEYS[1], ARGV[2], value); " +
                "                redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), ARGV[2]); " +
                "            end ;" +
                "            expireDate = math.min(expireDate, tonumber(expireIdle)) " +
                "        end; " +
                "    end; " +
                "    if expireDate <= tonumber(ARGV[1]) then " +
                "        return 0; " +
                "    end; " +
                "    return 1;" +
                "end;" +
                "return 0; ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getLastAccessTimeSetNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), encodeMapKey(key));
    }

    @Override
    public RFuture<Boolean> containsValueAsync(Object value) {
        checkValue(value);

        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
        "local s = redis.call('hgetall', KEYS[1]); " +
                "for i, v in ipairs(s) do " +
                "    if i % 2 == 0 then " +
                "        local t, val = struct.unpack('dLc0', v); " +
                "        if ARGV[2] == val then " +
                "            local key = s[i - 1]; " +
                "" +
                "            local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); " +
                "            if maxSize ~= nil and maxSize ~= 0 then " +
                "                redis.call('zadd', KEYS[4], tonumber(ARGV[1]), key); " +
                "            end; " +
                "" +
                "            local expireDate = 92233720368547758; " +
                "            local expireDateScore = redis.call('zscore', KEYS[2], key); " +
                "            if expireDateScore ~= false then " +
                "                expireDate = tonumber(expireDateScore) " +
                "            end; " +
                "            if t ~= 0 then " +
                "                local expireIdle = redis.call('zscore', KEYS[3], key); " +
                "                if expireIdle ~= false then " +
                "                    if tonumber(expireIdle) > tonumber(ARGV[1]) then " +
                "                        local value = struct.pack('dLc0', t, string.len(val), val); " +
                "                        redis.call('hset', KEYS[1], key, value); " +
                "                        redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), key); " +
                "                    end; " +
                "                    expireDate = math.min(expireDate, tonumber(expireIdle)) " +
                "                end; " +
                "            end; " +
                "            if expireDate <= tonumber(ARGV[1]) then " +
                "                return 0; " +
                "            end; " +
                "            return 1; " +
                "        end;" +
                "    end;" +
                "end;" +
                "return 0;",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()),
                System.currentTimeMillis(), encodeMapValue(value));
    }

    @Override
    protected RFuture<Map<K, V>> getAllOperationAsync(Set<K> keys) {
        List<Object> args = new ArrayList<Object>(keys.size() + 1);
        List<Object> plainKeys = new ArrayList<Object>(keys.size());
        
        args.add(System.currentTimeMillis());
        for (K key : keys) {
            plainKeys.add(key);
            args.add(encodeMapKey(key));
        }

        return commandExecutor.evalWriteAsync(getName(), codec, new RedisCommand<Map<Object, Object>>("EVAL", new MapGetAllDecoder(plainKeys, 0), ValueType.MAP_VALUE),
            "local expireHead = redis.call('zrange', KEYS[2], 0, 0, 'withscores'); " +
            "local currentTime = tonumber(table.remove(ARGV, 1)); " + // index is the first parameter
            "local hasExpire = #expireHead == 2 and tonumber(expireHead[2]) <= currentTime; " +
            "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size'));" +
            "local map = {}; " +
            "for i = 1, #ARGV, 1 do " +
            "    local value = redis.call('hget', KEYS[1], ARGV[i]); " + 
            "    map[i] = false;" +
            "    if value ~= false then " +
            "        local key = ARGV[i]; " +
            "        local t, val = struct.unpack('dLc0', value); " +
            "        map[i] = val; " +
            "        if maxSize ~= nil and maxSize ~= 0 then " +
            "            redis.call('zadd', KEYS[4], currentTime, key); " +
            "        end; " +
            "        if hasExpire then " +
            "            local expireDate = redis.call('zscore', KEYS[2], key); " +
            "            if expireDate ~= false and tonumber(expireDate) <= currentTime then " +
            "                map[i] = false; " +
            "            end; " +
            "        end; " +
            "        if t ~= 0 then " +
            "            local expireIdle = redis.call('zscore', KEYS[3], key); " +
            "            if expireIdle ~= false then " +
            "                if tonumber(expireIdle) > currentTime then " +
            "                    local value = struct.pack('dLc0', t, string.len(val), val); " +
            "                    redis.call('hset', KEYS[1], key, value); " +
            "                    redis.call('zadd', KEYS[3], t + currentTime, key); " +
            "                else " +
            "                    map[i] = false; " +
            "                end; " +
            "            end; " +
            "        end; " +
            "    end; " +
            "end; " +
            "return map;",
            Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()), 
            args.toArray());
    }

    @Override
    public V putIfAbsent(K key, V value, long ttl, TimeUnit ttlUnit) {
        return get(putIfAbsentAsync(key, value, ttl, ttlUnit));
    }

    @Override
    public RFuture<V> putIfAbsentAsync(K key, V value, long ttl, TimeUnit ttlUnit) {
        return putIfAbsentAsync(key, value, ttl, ttlUnit, 0, null);
    }

    @Override
    public V putIfAbsent(K key, V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        return get(putIfAbsentAsync(key, value, ttl, ttlUnit, maxIdleTime, maxIdleUnit));
    }

    @Override
    public RFuture<V> putIfAbsentAsync(final K key, final V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        checkKey(key);
        checkValue(value);
        
        if (ttl < 0) {
            throw new IllegalArgumentException("ttl can't be negative");
        }
        if (maxIdleTime < 0) {
            throw new IllegalArgumentException("maxIdleTime can't be negative");
        }
        if (ttl == 0 && maxIdleTime == 0) {
            return putIfAbsentAsync(key, value);
        }

        if (ttl > 0 && ttlUnit == null) {
            throw new NullPointerException("ttlUnit param can't be null");
        }

        if (maxIdleTime > 0 && maxIdleUnit == null) {
            throw new NullPointerException("maxIdleUnit param can't be null");
        }

        long ttlTimeout = 0;
        if (ttl > 0) {
            ttlTimeout = System.currentTimeMillis() + ttlUnit.toMillis(ttl);
        }

        long maxIdleTimeout = 0;
        long maxIdleDelta = 0;
        if (maxIdleTime > 0) {
            maxIdleDelta = maxIdleUnit.toMillis(maxIdleTime);
            maxIdleTimeout = System.currentTimeMillis() + maxIdleDelta;
        }

        RFuture<V> future = commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_MAP_VALUE,
                "local insertable = false; "
                        + "local value = redis.call('hget', KEYS[1], ARGV[5]); "
                            + "if value == false then "
                            + "insertable = true; "
                        + "else "
                            + "local t, val = struct.unpack('dLc0', value); "
                            + "local expireDate = 92233720368547758; "
                            + "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[5]); "
                            + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                            + "end; "
                            + "if t ~= 0 then "
                                + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[5]); "
                                + "if expireIdle ~= false then "
                                    + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                + "end; "
                            + "end; "
                            + "if expireDate <= tonumber(ARGV[1]) then "
                                + "insertable = true; "
                            + "end; "
                        + "end; "

                        + "if insertable == true then "
                            // ttl
                            + "if tonumber(ARGV[2]) > 0 then "
                                + "redis.call('zadd', KEYS[2], ARGV[2], ARGV[5]); "
                            + "else "
                                + "redis.call('zrem', KEYS[2], ARGV[5]); "
                            + "end; "

                            // idle
                            + "if tonumber(ARGV[3]) > 0 then "
                                + "redis.call('zadd', KEYS[3], ARGV[3], ARGV[5]); "
                            + "else "
                                + "redis.call('zrem', KEYS[3], ARGV[5]); "
                            + "end; "

                            // last access time
                            + "local maxSize = tonumber(redis.call('hget', KEYS[7], 'max-size')); " +
                            "if maxSize ~= nil and maxSize ~= 0 then " +
                            "    local currentTime = tonumber(ARGV[1]); " +
                            "    local lastAccessTimeSetName = KEYS[5]; " +
                            "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[5]); " +
                            "    local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                            "    if cacheSize >= maxSize then " +
                            "        local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize); " +
                            "        for index, lruItem in ipairs(lruItems) do " +
                            "            if lruItem then " +
                            "                local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                            "                redis.call('hdel', KEYS[1], lruItem); " +
                            "                redis.call('zrem', KEYS[2], lruItem); " +
                            "                redis.call('zrem', KEYS[3], lruItem); " +
                            "                redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                            "                local removedChannelName = KEYS[6]; " +
                            "                local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                            "                redis.call('publish', removedChannelName, msg); " +
                            "            end; " +
                            "        end; " +
                            "    end; " +
                            "end; "

                            // value
                            + "local val = struct.pack('dLc0', tonumber(ARGV[4]), string.len(ARGV[6]), ARGV[6]); "
                            + "redis.call('hset', KEYS[1], ARGV[5], val); "

                            + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[5]), ARGV[5], string.len(ARGV[6]), ARGV[6]); "
                            + "redis.call('publish', KEYS[4], msg); "

                            + "return nil; "
                        + "else "
                            + "local t, val = struct.unpack('dLc0', value); "
                            + "redis.call('zadd', KEYS[3], t + ARGV[1], ARGV[5]); "
                            + "return val; "
                        + "end; ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), ttlTimeout, maxIdleTimeout, maxIdleDelta, encodeMapKey(key), encodeMapValue(value));
        if (hasNoWriter()) {
            return future;
        }

        MapWriterTask<V> listener = new MapWriterTask<V>() {
            @Override
            protected void execute() {
                options.getWriter().write(key, value);
            }

            @Override
            protected boolean condition(Future<V> future) {
                return future.getNow() == null;
            }
        };
        return mapWriterFuture(future, listener);
    }

    @Override
    protected RFuture<Boolean> removeOperationAsync(Object key, Object value) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_BOOLEAN,
                "local value = redis.call('hget', KEYS[1], ARGV[2]); "
                        + "if value == false then "
                            + "return 0; "
                        + "end; "
                        + "local t, val = struct.unpack('dLc0', value); "
                        + "local expireDate = 92233720368547758; " +
                        "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                        + "if expireDateScore ~= false then "
                            + "expireDate = tonumber(expireDateScore) "
                        + "end; "
                        + "if t ~= 0 then "
                            + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); "
                            + "if expireIdle ~= false then "
                                + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                            + "end; "
                        + "end; "
                        + "if expireDate <= tonumber(ARGV[1]) then "
                            + "return 0; "
                        + "end; "

                        + "if val == ARGV[3] then "
                            + "redis.call('zrem', KEYS[2], ARGV[2]); "
                            + "redis.call('zrem', KEYS[3], ARGV[2]); "
                            + "local maxSize = tonumber(redis.call('hget', KEYS[6], 'max-size')); " +
                                "if maxSize ~= nil and maxSize ~= 0 then " +
                                "   redis.call('zrem', KEYS[5], ARGV[2]); " +
                                "end; "
                            + "redis.call('hdel', KEYS[1], ARGV[2]); "
                            + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(val), val); "
                            + "redis.call('publish', KEYS[4], msg); "
                            + "return 1; "
                        + "else "
                            + "return 0; "
                        + "end",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getRemovedChannelNameByKey(key),
                        getLastAccessTimeSetNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    protected RFuture<V> getOperationAsync(K key) {
        checkKey(key);

        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_MAP_VALUE,
                "local value = redis.call('hget', KEYS[1], ARGV[2]); "
                        + "if value == false then "
                            + "return nil; "
                        + "end; "
                        + "local t, val = struct.unpack('dLc0', value); "
                        + "local expireDate = 92233720368547758; " +
                        "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                        + "if expireDateScore ~= false then "
                            + "expireDate = tonumber(expireDateScore) "
                        + "end; "
                        + "if t ~= 0 then "
                            + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); "
                            + "if expireIdle ~= false then "
                                + "if tonumber(expireIdle) > tonumber(ARGV[1]) then "
                                    + "local value = struct.pack('dLc0', t, string.len(val), val); "
                                    + "redis.call('hset', KEYS[1], ARGV[2], value); "
                                    + "redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), ARGV[2]); "
                                + "end; "
                                + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                            + "end; "
                        + "end; "
                        + "if expireDate <= tonumber(ARGV[1]) then "
                            + "return nil; "
                        + "end; "
                        + "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); " +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "   redis.call('zadd', KEYS[4], tonumber(ARGV[1]), ARGV[2]); " +
                        "end; "
                        + "return val; ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getLastAccessTimeSetNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), encodeMapKey(key));
    }

    @Override
    public V put(K key, V value, long ttl, TimeUnit unit) {
        return get(putAsync(key, value, ttl, unit));
    }

    @Override
    protected RFuture<V> putOperationAsync(K key, V value) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_MAP_VALUE,
                "local v = redis.call('hget', KEYS[1], ARGV[2]);" +
                "local exists = false;" +
                "if v ~= false then" +
                "    local t, val = struct.unpack('dLc0', v);" +
                "    local expireDate = 92233720368547758;" +
                "    local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]);" +
                "    if expireDateScore ~= false then" +
                "        expireDate = tonumber(expireDateScore)" +
                "    end;" +
                "    if t ~= 0 then" +
                "        local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]);" +
                "        if expireIdle ~= false then" +
                "            expireDate = math.min(expireDate, tonumber(expireIdle))" +
                "        end;" +
                "    end;" +
                "    if expireDate > tonumber(ARGV[1]) then" +
                "        exists = true;" +
                "    end;" +
                "end;" +
                "" +
                "local value = struct.pack('dLc0', 0, string.len(ARGV[3]), ARGV[3]);" +
                "redis.call('hset', KEYS[1], ARGV[2], value);" +
                "local currentTime = tonumber(ARGV[1]);" +
                "local lastAccessTimeSetName = KEYS[6];" +
                "local maxSize = tonumber(redis.call('hget', KEYS[8], 'max-size'));" +
                "if exists == false then" +
                "    if maxSize ~= nil and maxSize ~= 0 then" +
                "        redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]);" +
                "        local cacheSize = tonumber(redis.call('hlen', KEYS[1]));" +
                "        if cacheSize > maxSize then" +
                "            local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize - 1);" +
                "            for index, lruItem in ipairs(lruItems) do" +
                "                if lruItem then" +
                "                    local lruItemValue = redis.call('hget', KEYS[1], lruItem);" +
                "                    redis.call('hdel', KEYS[1], lruItem);" +
                "                    redis.call('zrem', KEYS[2], lruItem);" +
                "                    redis.call('zrem', KEYS[3], lruItem);" +
                "                    redis.call('zrem', lastAccessTimeSetName, lruItem);" +
                "                    local removedChannelName = KEYS[7];" +
                "                    local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue);" +
                "                    redis.call('publish', removedChannelName, msg);" +
                "                end;" +
                "            end" +
                "        end;" +
                "    end;" +
                "    local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3]);" +
                "    redis.call('publish', KEYS[4], msg);" +
                "    return nil;" +
                "else" +
                "    if maxSize ~= nil and maxSize ~= 0 then" +
                "        redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]);" +
                "    end;" +
                "end;" +
                "" +
                "local t, val = struct.unpack('dLc0', v);" +
                "local msg = struct.pack('Lc0Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3], string.len(val), val);" +
                "redis.call('publish', KEYS[5], msg);" +
                "return val;",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getUpdatedChannelNameByKey(key), getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    protected RFuture<V> putIfAbsentOperationAsync(K key, V value) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_MAP_VALUE,
                "local value = redis.call('hget', KEYS[1], ARGV[2]); "
                        + "local maxSize = tonumber(redis.call('hget', KEYS[7], 'max-size'));"
                        + "local lastAccessTimeSetName = KEYS[5]; "
                        + "local currentTime = tonumber(ARGV[1]); "
                        + "if value ~= false then "
                            + "local t, val = struct.unpack('dLc0', value); "
                            + "local expireDate = 92233720368547758; "
                            + "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                            + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                            + "end; "
                            + "if t ~= 0 then "
                                + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); "
                                + "if expireIdle ~= false then "
                                    + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                + "end; "
                            + "end; "
                            + "if expireDate > tonumber(ARGV[1]) then "
                                + "if maxSize ~= nil and maxSize ~= 0 then "
                                + "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]); "
                                + "end; "
                                + "return val; "
                            + "end; "
                        + "end; "

                        + "local value = struct.pack('dLc0', 0, string.len(ARGV[3]), ARGV[3]); "
                        + "redis.call('hset', KEYS[1], ARGV[2], value); "

                        // last access time
                        + "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]); " +
                        "    local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                        "    if cacheSize > maxSize then " +
                        "        local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize - 1); " +
                        "        for index, lruItem in ipairs(lruItems) do " +
                        "            if lruItem then " +
                        "                local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                        "                redis.call('hdel', KEYS[1], lruItem); " +
                        "                redis.call('zrem', KEYS[2], lruItem); " +
                        "                redis.call('zrem', KEYS[3], lruItem); " +
                        "                redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                        "                local removedChannelName = KEYS[6]; " +
                        "                local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                        "                redis.call('publish', removedChannelName, msg); " +
                        "            end; " +
                        "        end; " +
                        "    end; " +
                        "end; "

                        + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3]); "
                        + "redis.call('publish', KEYS[4], msg); "
                        + "return nil;",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    public V addAndGet(K key, Number value) {
        return get(addAndGetAsync(key, value));
    }

    @Override
    public RFuture<V> addAndGetOperationAsync(K key, Number value) {
        ByteBuf keyState = encodeMapKey(key);
        return commandExecutor.evalWriteAsync(getName(key), StringCodec.INSTANCE,
                new RedisCommand<Object>("EVAL", new NumberConvertor(value.getClass())),
                "local value = redis.call('hget', KEYS[1], ARGV[2]); "
                        + "local expireDate = 92233720368547758; "
                        + "local t = 0; "
                        + "local val = 0; "
                        + "if value ~= false then "
                            + "t, val = struct.unpack('dLc0', value); "
                            + "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                            + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                            + "end; "
                            + "if t ~= 0 then "
                                + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); "
                                + "if expireIdle ~= false then "
                                    + "if tonumber(expireIdle) > tonumber(ARGV[1]) then "
                                        + "local value = struct.pack('dLc0', t, string.len(val), val); "
                                        + "redis.call('hset', KEYS[1], ARGV[2], value); "
                                        + "redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), ARGV[2]); "
                                    + "end; "
                                    + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                + "end; "
                            + "end; "
                        + "end; "

                        + "local newValue = tonumber(ARGV[3]); "
                        + "if value ~= false and expireDate > tonumber(ARGV[1]) then "
                            + "newValue = tonumber(val) + newValue; "

                            + "local msg = struct.pack('Lc0Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(newValue), newValue, string.len(val), val); "
                            + "redis.call('publish', KEYS[5], msg); "
                        + "else "
                            + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3]); "
                            + "redis.call('publish', KEYS[4], msg); "
                        + "end; "
                        + "local newValuePack = struct.pack('dLc0', t + tonumber(ARGV[1]), string.len(newValue), newValue); "
                        + "redis.call('hset', KEYS[1], ARGV[2], newValuePack); "

                        // last access time
                        + "local maxSize = tonumber(redis.call('hget', KEYS[8], 'max-size')); " +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    local currentTime = tonumber(ARGV[1]); " +
                        "    local lastAccessTimeSetName = KEYS[6]; " +
                        "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]); " +
                        "    local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                        "    if cacheSize > maxSize then " +
                        "        local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize - 1); " +
                        "        for index, lruItem in ipairs(lruItems) do " +
                        "            if lruItem then " +
                        "                local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                        "                redis.call('hdel', KEYS[1], lruItem); " +
                        "                redis.call('zrem', KEYS[2], lruItem); " +
                        "                redis.call('zrem', KEYS[3], lruItem); " +
                        "                redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                        "                local removedChannelName = KEYS[7]; " +
                        "                local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                        "                redis.call('publish', removedChannelName, msg); " +
                        "            end; " +
                        "        end; " +
                        "    end; " +
                        "end; "

                        + "return tostring(newValue); ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getUpdatedChannelNameByKey(key), getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), keyState, new BigDecimal(value.toString()).toPlainString());
    }

    @Override
    public boolean fastPut(K key, V value, long ttl, TimeUnit ttlUnit) {
        return get(fastPutAsync(key, value, ttl, ttlUnit));
    }

    @Override
    public RFuture<Boolean> fastPutAsync(K key, V value, long ttl, TimeUnit ttlUnit) {
        return fastPutAsync(key, value, ttl, ttlUnit, 0, null);
    }

    @Override
    public boolean fastPut(K key, V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        return get(fastPutAsync(key, value, ttl, ttlUnit, maxIdleTime, maxIdleUnit));
    }

    @Override
    public RFuture<Boolean> fastPutAsync(final K key, final V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        checkKey(key);
        checkValue(value);

        if (ttl < 0) {
            throw new IllegalArgumentException("ttl can't be negative");
        }
        if (maxIdleTime < 0) {
            throw new IllegalArgumentException("maxIdleTime can't be negative");
        }
        if (ttl == 0 && maxIdleTime == 0) {
            return fastPutAsync(key, value);
        }

        if (ttl > 0 && ttlUnit == null) {
            throw new NullPointerException("ttlUnit param can't be null");
        }

        if (maxIdleTime > 0 && maxIdleUnit == null) {
            throw new NullPointerException("maxIdleUnit param can't be null");
        }

        RFuture<Boolean> future = fastPutOperationAsync(key, value, ttl, ttlUnit, maxIdleTime, maxIdleUnit);

        if (hasNoWriter()) {
            return future;
        }

        MapWriterTask<Boolean> listener = new MapWriterTask<Boolean>() {
            @Override
            protected void execute() {
                options.getWriter().write(key, value);
            }
        };
        return mapWriterFuture(future, listener);
    }

    protected RFuture<Boolean> fastPutOperationAsync(K key, V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        long currentTime = System.currentTimeMillis();
        long ttlTimeout = 0;
        if (ttl > 0) {
            ttlTimeout = currentTime + ttlUnit.toMillis(ttl);
        }

        long maxIdleTimeout = 0;
        long maxIdleDelta = 0;
        if (maxIdleTime > 0) {
            maxIdleDelta = maxIdleUnit.toMillis(maxIdleTime);
            maxIdleTimeout = currentTime + maxIdleDelta;
        }

        RFuture<Boolean> future = commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_BOOLEAN,
                "local insertable = false; "
                        + "local value = redis.call('hget', KEYS[1], ARGV[5]); "
                        + "local t, val;"
                        + "if value == false then "
                            + "insertable = true; "
                        + "else "
                            + "t, val = struct.unpack('dLc0', value); "
                            + "local expireDate = 92233720368547758; "
                            + "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[5]); "
                            + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                            + "end; "
                            + "if t ~= 0 then "
                                + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[5]); "
                                + "if expireIdle ~= false then "
                                    + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                + "end; "
                            + "end; "
                            + "if expireDate <= tonumber(ARGV[1]) then "
                                + "insertable = true; "
                            + "end; "
                        + "end; " +

                        "if tonumber(ARGV[2]) > 0 then "
                            + "redis.call('zadd', KEYS[2], ARGV[2], ARGV[5]); "
                        + "else "
                            + "redis.call('zrem', KEYS[2], ARGV[5]); "
                        + "end; "
                        + "if tonumber(ARGV[3]) > 0 then "
                            + "redis.call('zadd', KEYS[3], ARGV[3], ARGV[5]); "
                        + "else "
                            + "redis.call('zrem', KEYS[3], ARGV[5]); "
                        + "end; " +

                        // last access time
                        "local maxSize = tonumber(redis.call('hget', KEYS[8], 'max-size')); " +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    local currentTime = tonumber(ARGV[1]); " +
                        "    local lastAccessTimeSetName = KEYS[6]; " +
                        "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[5]); " +
                        "    local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                        "    if cacheSize >= maxSize then " +
                        "        local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize); " +
                        "        for index, lruItem in ipairs(lruItems) do " +
                        "            if lruItem then " +
                        "                local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                        "                redis.call('hdel', KEYS[1], lruItem); " +
                        "                redis.call('zrem', KEYS[2], lruItem); " +
                        "                redis.call('zrem', KEYS[3], lruItem); " +
                        "                redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                        "                local removedChannelName = KEYS[7]; " +
                        "                local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                        "                redis.call('publish', removedChannelName, msg); " +
                        "            end; " +
                        "        end; " +
                        "    end; " +
                        "end; "

                        + "local value = struct.pack('dLc0', ARGV[4], string.len(ARGV[6]), ARGV[6]); "
                        + "redis.call('hset', KEYS[1], ARGV[5], value); "
                        + "if insertable == true then "
                            + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[5]), ARGV[5], string.len(ARGV[6]), ARGV[6]); "
                            + "redis.call('publish', KEYS[4], msg); "
                            + "return 1;"
                        + "else "
                            + "local msg = struct.pack('Lc0Lc0Lc0', string.len(ARGV[5]), ARGV[5], string.len(ARGV[6]), ARGV[6], string.len(val), val); "
                            + "redis.call('publish', KEYS[5], msg); "
                            + "return 0;"
                        + "end;",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getUpdatedChannelNameByKey(key), getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), ttlTimeout, maxIdleTimeout, maxIdleDelta, encodeMapKey(key), encodeMapValue(value));
        return future;
    }

    @Override
    public RFuture<V> putAsync(K key, V value, long ttl, TimeUnit ttlUnit) {
        return putAsync(key, value, ttl, ttlUnit, 0, null);
    }

    @Override
    public V put(K key, V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        return get(putAsync(key, value, ttl, ttlUnit, maxIdleTime, maxIdleUnit));
    }

    @Override
    public RFuture<V> putAsync(final K key, final V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        checkKey(key);
        checkValue(value);

        if (ttl < 0) {
            throw new IllegalArgumentException("ttl can't be negative");
        }
        if (maxIdleTime < 0) {
            throw new IllegalArgumentException("maxIdleTime can't be negative");
        }
        if (ttl == 0 && maxIdleTime == 0) {
            return putAsync(key, value);
        }

        if (ttl > 0 && ttlUnit == null) {
            throw new NullPointerException("ttlUnit param can't be null");
        }

        if (maxIdleTime > 0 && maxIdleUnit == null) {
            throw new NullPointerException("maxIdleUnit param can't be null");
        }

        long ttlTimeout = 0;
        if (ttl > 0) {
            ttlTimeout = System.currentTimeMillis() + ttlUnit.toMillis(ttl);
        }

        long maxIdleTimeout = 0;
        long maxIdleDelta = 0;
        if (maxIdleTime > 0) {
            maxIdleDelta = maxIdleUnit.toMillis(maxIdleTime);
            maxIdleTimeout = System.currentTimeMillis() + maxIdleDelta;
        }

        RFuture<V> future = putOperationAsync(key, value, ttlTimeout, maxIdleTimeout, maxIdleDelta);
        if (hasNoWriter()) {
            return future;
        }

        MapWriterTask<V> listener = new MapWriterTask<V>() {
            @Override
            protected void execute() {
                options.getWriter().write(key, value);
            }
        };
        return mapWriterFuture(future, listener);
    }

    protected RFuture<V> putOperationAsync(final K key, final V value, long ttlTimeout, long maxIdleTimeout,
            long maxIdleDelta) {
        RFuture<V> future = commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_MAP_VALUE,
                "local insertable = false; "
                        + "local v = redis.call('hget', KEYS[1], ARGV[5]); "
                        + "if v == false then "
                            + "insertable = true; "
                        + "else "
                            + "local t, val = struct.unpack('dLc0', v); "
                            + "local expireDate = 92233720368547758; "
                            + "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[5]); "
                            + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                            + "end; "
                            + "if t ~= 0 then "
                                + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[5]); "
                                + "if expireIdle ~= false then "
                                    + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                + "end; "
                            + "end; "
                            + "if expireDate <= tonumber(ARGV[1]) then "
                                + "insertable = true; "
                            + "end; "
                        + "end; "

                        + "if tonumber(ARGV[2]) > 0 then "
                            + "redis.call('zadd', KEYS[2], ARGV[2], ARGV[5]); "
                        + "else "
                            + "redis.call('zrem', KEYS[2], ARGV[5]); "
                        + "end; "
                        + "if tonumber(ARGV[3]) > 0 then "
                            + "redis.call('zadd', KEYS[3], ARGV[3], ARGV[5]); "
                        + "else "
                            + "redis.call('zrem', KEYS[3], ARGV[5]); "
                        + "end; "

                        // last access time
                        + "local maxSize = tonumber(redis.call('hget', KEYS[8], 'max-size')); " +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    local currentTime = tonumber(ARGV[1]); " +
                        "    local lastAccessTimeSetName = KEYS[6]; " +
                        "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[5]); " +
                        "    local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                        "    if cacheSize >= maxSize then " +
                        "        local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize); " +
                        "        for index, lruItem in ipairs(lruItems) do " +
                        "            if lruItem then " +
                        "                local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                        "                redis.call('hdel', KEYS[1], lruItem); " +
                        "                redis.call('zrem', KEYS[2], lruItem); " +
                        "                redis.call('zrem', KEYS[3], lruItem); " +
                        "                redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                        "                local removedChannelName = KEYS[7]; " +
                        "                local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                        "                redis.call('publish', removedChannelName, msg); " +
                        "            end; " +
                        "        end; " +
                        "    end; " +
                        "end; "

                        + "local value = struct.pack('dLc0', ARGV[4], string.len(ARGV[6]), ARGV[6]); "
                        + "redis.call('hset', KEYS[1], ARGV[5], value); "

                        + "if insertable == true then "
                            + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[5]), ARGV[5], string.len(ARGV[6]), ARGV[6]); "
                            + "redis.call('publish', KEYS[4], msg); "
                            + "return nil;"
                        + "end; "

                        + "local t, val = struct.unpack('dLc0', v); "

                        + "local msg = struct.pack('Lc0Lc0Lc0', string.len(ARGV[5]), ARGV[5], string.len(ARGV[6]), ARGV[6], string.len(val), val); "
                        + "redis.call('publish', KEYS[5], msg); "

                        + "return val",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getUpdatedChannelNameByKey(key), getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), ttlTimeout, maxIdleTimeout, maxIdleDelta, encodeMapKey(key), encodeMapValue(value));
        return future;
    }

    String getTimeoutSetNameByKey(Object key) {
        return prefixName("redisson__timeout__set", getName(key));
    }

    String getTimeoutSetName(String name) {
        return prefixName("redisson__timeout__set", name);
    }

    String getTimeoutSetName() {
        return prefixName("redisson__timeout__set", getName());
    }
    
    String getLastAccessTimeSetNameByKey(Object key) {
        return prefixName("redisson__map_cache__last_access__set", getName(key));
    }

    String getLastAccessTimeSetName() {
        return prefixName("redisson__map_cache__last_access__set", getName());
    }

    String getIdleSetNameByKey(Object key) {
        return prefixName("redisson__idle__set", getName(key));
    }

    String getIdleSetName(String name) {
        return prefixName("redisson__idle__set", name);
    }

    String getIdleSetName() {
        return prefixName("redisson__idle__set", getName());
    }

    String getOptionsName() {
        return suffixName(getName(), "redisson_options");
    }
    
    String getOptionsName(Object key) {
        return suffixName(getName(key), "redisson_options");
    }
    
    String getCreatedChannelNameByKey(Object key) {
        return prefixName("redisson_map_cache_created", getName(key));
    }

    String getCreatedChannelName(String name) {
        return prefixName("redisson_map_cache_created", name);
    }

    String getCreatedChannelName() {
        return prefixName("redisson_map_cache_created", getName());
    }

    String getUpdatedChannelNameByKey(Object key) {
        return prefixName("redisson_map_cache_updated", getName(key));
    }
    
    String getUpdatedChannelName() {
        return prefixName("redisson_map_cache_updated", getName());
    }
    
    String getUpdatedChannelName(String name) {
        return prefixName("redisson_map_cache_updated", name);
    }

    String getExpiredChannelNameByKey(Object key) {
        return prefixName("redisson_map_cache_expired", getName(key));
    }

    String getExpiredChannelName(String name) {
        return prefixName("redisson_map_cache_expired", name);
    }
    
    String getExpiredChannelName() {
        return prefixName("redisson_map_cache_expired", getName());
    }

    String getRemovedChannelNameByKey(Object key) {
        return prefixName("redisson_map_cache_removed", getName(key));
    }
    
    String getRemovedChannelName() {
        return prefixName("redisson_map_cache_removed", getName());
    }
    
    String getRemovedChannelName(String name) {
        return prefixName("redisson_map_cache_removed", name);
    }


    @Override
    public RFuture<V> removeOperationAsync(K key) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_MAP_VALUE,
                "local value = redis.call('hget', KEYS[1], ARGV[2]); "
                        + "if value == false then "
                            + "return nil; "
                        + "end; "

                        + "local t, val = struct.unpack('dLc0', value); "
                        + "local expireDate = 92233720368547758; " +
                        "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                        + "if expireDateScore ~= false then "
                            + "expireDate = tonumber(expireDateScore) "
                        + "end; "
                        + "if t ~= 0 then "
                            + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); "
                            + "if expireIdle ~= false then "
                                + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                            + "end; "
                        + "end; "
                        + "if expireDate <= tonumber(ARGV[1]) then "
                            + "return nil; "
                        + "end; "

                        + "redis.call('zrem', KEYS[2], ARGV[2]); "
                        + "redis.call('zrem', KEYS[3], ARGV[2]); "
                        + "redis.call('zrem', KEYS[5], ARGV[2]); "
                        + "redis.call('hdel', KEYS[1], ARGV[2]); "

                        + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(val), val); "
                        + "redis.call('publish', KEYS[4], msg); "
                        + "return val; ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getRemovedChannelNameByKey(key),
                        getLastAccessTimeSetNameByKey(key)),
                System.currentTimeMillis(), encodeMapKey(key));
    }

    @Override
    protected RFuture<List<Long>> fastRemoveOperationBatchAsync(K... keys) {
        List<Object> args = new ArrayList<Object>(keys.length);
        for (K key : keys) {
            args.add(encodeMapKey(key));
        }

        RFuture<List<Long>> future = commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_LIST,
                "local maxSize = tonumber(redis.call('hget', KEYS[6], 'max-size')); "
                        + "if maxSize ~= nil and maxSize ~= 0 then "
                        + "    redis.call('zrem', KEYS[5], unpack(ARGV)); "
                        + "end; " +
                        "redis.call('zrem', KEYS[3], unpack(ARGV)); " +
                        "redis.call('zrem', KEYS[2], unpack(ARGV)); " +
                        "for i, key in ipairs(ARGV) do "
                        + "local v = redis.call('hget', KEYS[1], key); "
                        + "if v ~= false then "
                            + "local t, val = struct.unpack('dLc0', v); "
                            + "local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(val), val); "
                            + "redis.call('publish', KEYS[4], msg); "
                        + "end; " +
                        "end; " +

                        "local result = {}; " +
                        "for i = 1, #ARGV, 1 do "
                            + "local val = redis.call('hdel', KEYS[1], ARGV[i]); "
                            + "table.insert(result, val); "
                        + "end;"
                        + "return result;",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getRemovedChannelName(), getLastAccessTimeSetName(), getOptionsName()),
                args.toArray());
        return future;
    }

    @Override
    protected RFuture<Long> fastRemoveOperationAsync(K ... keys) {
        List<Object> params = new ArrayList<Object>(keys.length);
        for (K key : keys) {
            params.add(encodeMapKey(key));
        }

        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_LONG,
                "local maxSize = tonumber(redis.call('hget', KEYS[6], 'max-size')); "
                        + "if maxSize ~= nil and maxSize ~= 0 then "
                        + "    redis.call('zrem', KEYS[5], unpack(ARGV)); "
                        + "end; " +
                        "redis.call('zrem', KEYS[3], unpack(ARGV)); " +
                        "redis.call('zrem', KEYS[2], unpack(ARGV)); " +
                        "for i, key in ipairs(ARGV) do "
                        + "local v = redis.call('hget', KEYS[1], key); "
                        + "if v ~= false then "
                            + "local t, val = struct.unpack('dLc0', v); "
                            + "local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(val), val); "
                            + "redis.call('publish', KEYS[4], msg); "
                        + "end; " +
                        "end; " +
                        "return redis.call('hdel', KEYS[1], unpack(ARGV)); ",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getRemovedChannelName(), getLastAccessTimeSetName(), getOptionsName()),
                params.toArray());
    }

    @Override
    MapScanResult<ScanObjectEntry, ScanObjectEntry> scanIterator(String name, RedisClient client, long startPos, String pattern) {
        return get(scanIteratorAsync(name, client, startPos, pattern));
    }

    public RFuture<MapScanResult<ScanObjectEntry, ScanObjectEntry>> scanIteratorAsync(final String name, RedisClient client, long startPos, String pattern) {
        List<Object> params = new ArrayList<Object>();
        params.add(System.currentTimeMillis());
        params.add(startPos);
        if (pattern != null) {
            params.add(pattern);
        }

        RedisCommand<MapCacheScanResult<Object, Object>> EVAL_HSCAN = new RedisCommand<MapCacheScanResult<Object, Object>>("EVAL",
                new ListMultiDecoder(new LongMultiDecoder(), new ObjectMapDecoder(new MapScanCodec(codec)), new ObjectListDecoder(codec), new MapCacheScanResultReplayDecoder()), ValueType.MAP);
        RFuture<MapCacheScanResult<ScanObjectEntry, ScanObjectEntry>> f = commandExecutor.evalReadAsync(client, name, codec, EVAL_HSCAN,
                "local result = {}; "
                + "local idleKeys = {}; "
                + "local res; "
                + "if (#ARGV == 3) then "
                    + " res = redis.call('hscan', KEYS[1], ARGV[2], 'match', ARGV[3]); "
                + "else "
                    + " res = redis.call('hscan', KEYS[1], ARGV[2]); "
                + "end;"
                + "local currentTime = tonumber(ARGV[1]); "
                + "for i, value in ipairs(res[2]) do "
                    + "if i % 2 == 0 then "
                      + "local key = res[2][i-1]; " +
                        "local expireDate = 92233720368547758; " +
                        "local expireDateScore = redis.call('zscore', KEYS[2], key); "
                        + "if expireDateScore ~= false then "
                            + "expireDate = tonumber(expireDateScore) "
                        + "end; "

                        + "local t, val = struct.unpack('dLc0', value); "
                        + "if t ~= 0 then "
                            + "local expireIdle = redis.call('zscore', KEYS[3], key); "
                            + "if expireIdle ~= false then "
                                + "if tonumber(expireIdle) > currentTime and expireDate > currentTime then "
                                    + "table.insert(idleKeys, key); "
                                + "end; "
                                + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                            + "end; "
                        + "end; "

                        + "if expireDate > currentTime then "
                            + "table.insert(result, key); "
                            + "table.insert(result, val); "
                        + "end; "
                    + "end; "
                + "end;"
                + "return {res[1], result, idleKeys};",
                Arrays.<Object>asList(name, getTimeoutSetName(name), getIdleSetName(name)),
                params.toArray());

        f.addListener(new FutureListener<MapCacheScanResult<ScanObjectEntry, ScanObjectEntry>>() {
            @Override
            public void operationComplete(Future<MapCacheScanResult<ScanObjectEntry, ScanObjectEntry>> future)
                    throws Exception {
                if (future.isSuccess()) {
                    MapCacheScanResult<ScanObjectEntry, ScanObjectEntry> res = future.getNow();
                    if (res.getIdleKeys().isEmpty()) {
                        return;
                    }

                    List<Object> args = new ArrayList<Object>(res.getIdleKeys().size() + 1);
                    args.add(System.currentTimeMillis());
                    encodeMapKeys(args, res.getIdleKeys());

                    commandExecutor.evalWriteAsync(name, codec, new RedisCommand<Map<Object, Object>>("EVAL", new MapGetAllDecoder(args, 1), ValueType.MAP_VALUE),
                                    "local currentTime = tonumber(table.remove(ARGV, 1)); " // index is the first parameter
                                  + "local map = redis.call('hmget', KEYS[1], unpack(ARGV)); "
                                  + "for i = #map, 1, -1 do "
                                      + "local value = map[i]; "
                                      + "if value ~= false then "
                                          + "local key = ARGV[i]; "
                                          + "local t, val = struct.unpack('dLc0', value); "

                                          + "if t ~= 0 then "
                                              + "local expireIdle = redis.call('zscore', KEYS[2], key); "
                                              + "if expireIdle ~= false then "
                                                  + "if tonumber(expireIdle) > currentTime then "
                                                      + "local value = struct.pack('dLc0', t, string.len(val), val); "
                                                      + "redis.call('hset', KEYS[1], key, value); "
                                                      + "redis.call('zadd', KEYS[2], t + currentTime, key); "
                                                  + "end; "
                                              + "end; "
                                          + "end; "
                                      + "end; "
                                  + "end; ",
                            Arrays.<Object>asList(name, getIdleSetName(name)), args.toArray());

                }
            }
        });

        return (RFuture<MapScanResult<ScanObjectEntry, ScanObjectEntry>>)(Object)f;
    }


    @Override
    protected RFuture<Boolean> fastPutOperationAsync(K key, V value) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_BOOLEAN,
                "local insertable = false; "
                        + "local v = redis.call('hget', KEYS[1], ARGV[2]); "
                        + "if v == false then "
                                + "insertable = true; "
                        + "else "
                            + "local t, val = struct.unpack('dLc0', v); "
                            + "local expireDate = 92233720368547758; "
                            + "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                            + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                            + "end; "
                            + "if t ~= 0 then "
                                + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); "
                                + "if expireIdle ~= false then "
                                    + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                + "end; "
                            + "end; "
                            + "if expireDate <= tonumber(ARGV[1]) then "
                                + "insertable = true; "
                            + "end; "
                        + "end; " +

                        "local val = struct.pack('dLc0', 0, string.len(ARGV[3]), ARGV[3]); "
                        + "redis.call('hset', KEYS[1], ARGV[2], val); " +

                        // last access time
                        "local maxSize = tonumber(redis.call('hget', KEYS[8], 'max-size'));" +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    local currentTime = tonumber(ARGV[1]); " +
                        "    local lastAccessTimeSetName = KEYS[6]; " +
                        "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]); " +
                        "    local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                        "    if cacheSize > maxSize then " +
                        "        local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize - 1); " +
                        "        for index, lruItem in ipairs(lruItems) do " +
                        "            if lruItem then " +
                        "                local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                        "                redis.call('hdel', KEYS[1], lruItem); " +
                        "                redis.call('zrem', KEYS[2], lruItem); " +
                        "                redis.call('zrem', KEYS[3], lruItem); " +
                        "                redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                        "                local removedChannelName = KEYS[7]; " +
                        "                local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                        "                redis.call('publish', removedChannelName, msg); " +
                        "            end; " +
                        "        end; " +
                        "    end; " +
                        "end; "

                        + "if insertable == true then "
                            + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3]); "
                            + "redis.call('publish', KEYS[4], msg); "
                            + "return 1;"
                        + "else "
                            + "local t, val = struct.unpack('dLc0', v); "
                            + "local msg = struct.pack('Lc0Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3], string.len(val), val); "
                            + "redis.call('publish', KEYS[5], msg); "
                            + "return 0;"
                        + "end;",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getUpdatedChannelNameByKey(key), getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    protected RFuture<Boolean> fastPutIfAbsentOperationAsync(K key, V value) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_BOOLEAN,
                "local value = redis.call('hget', KEYS[1], ARGV[2]); "
                        + "local lastAccessTimeSetName = KEYS[5]; "
                        + "local maxSize = tonumber(redis.call('hget', KEYS[7], 'max-size')); "
                        + "local currentTime = tonumber(ARGV[1]); "
                        + "if value == false then "
                            + "local val = struct.pack('dLc0', 0, string.len(ARGV[3]), ARGV[3]); "
                            + "redis.call('hset', KEYS[1], ARGV[2], val); "
                            + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3]); "
                            + "redis.call('publish', KEYS[4], msg); "+

                            // last access time

                            "if maxSize ~= nil and maxSize ~= 0 then " +
                            "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]); " +
                            "    local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                            "    if cacheSize > maxSize then " +
                            "        local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize - 1); " +
                            "        for index, lruItem in ipairs(lruItems) do " +
                            "            if lruItem then " +
                            "                local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                            "                redis.call('hdel', KEYS[1], lruItem); " +
                            "                redis.call('zrem', KEYS[2], lruItem); " +
                            "                redis.call('zrem', KEYS[3], lruItem); " +
                            "                redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                            "                local removedChannelName = KEYS[6]; " +
                            "                local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                            "                redis.call('publish', removedChannelName, msg); " +
                            "            end; " +
                            "        end; " +
                            "    end; " +
                            "end; "

                            + "return 1; "
                        + "end; "

                        + "if maxSize ~= nil and maxSize ~= 0 then "
                        + "    redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[2]); "
                        + "end; "
                        + "local t, val = struct.unpack('dLc0', value); "
                        + "local expireDate = 92233720368547758; "
                        + "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); "
                        + "if expireDateScore ~= false then "
                            + "expireDate = tonumber(expireDateScore) "
                        + "end; "
                        + "if t ~= 0 then "
                            + "local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); "
                            + "if expireIdle ~= false then "
                                + "if tonumber(expireIdle) > tonumber(ARGV[1]) then "
                                    + "local value = struct.pack('dLc0', t, string.len(val), val); "
                                    + "redis.call('hset', KEYS[1], ARGV[2], value); "
                                    + "redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), ARGV[2]); "
                                + "end; "
                                + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                            + "end; "
                        + "end; "
                        + "if expireDate > tonumber(ARGV[1]) then "
                            + "return 0; "
                        + "end; "

                        + "redis.call('zrem', KEYS[2], ARGV[2]); "
                        + "redis.call('zrem', KEYS[3], ARGV[2]); "
                        + "local val = struct.pack('dLc0', 0, string.len(ARGV[3]), ARGV[3]); "
                        + "redis.call('hset', KEYS[1], ARGV[2], val); "

                        + "local msg = struct.pack('Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3]); "
                        + "redis.call('publish', KEYS[4], msg); "
                        + "return 1; ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), encodeMapKey(key), encodeMapValue(value));
    }

    @Override
	public boolean fastPutIfAbsent(K key, V value, long ttl, TimeUnit ttlUnit) {
		return fastPutIfAbsent(key, value, ttl, ttlUnit, 0, null);
	}

    @Override
    public boolean fastPutIfAbsent(K key, V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
    	return get(fastPutIfAbsentAsync(key, value, ttl, ttlUnit, maxIdleTime, maxIdleUnit));
    }

    @Override
	public RFuture<Boolean> fastPutIfAbsentAsync(final K key, final V value, long ttl, TimeUnit ttlUnit, long maxIdleTime, TimeUnit maxIdleUnit) {
        checkKey(key);
        checkValue(value);

		if (ttl < 0) {
            throw new IllegalArgumentException("ttl can't be negative");
        }
        if (maxIdleTime < 0) {
            throw new IllegalArgumentException("maxIdleTime can't be negative");
        }
        if (ttl == 0 && maxIdleTime == 0) {
            return fastPutIfAbsentAsync(key, value);
        }

        if (ttl > 0 && ttlUnit == null) {
            throw new NullPointerException("ttlUnit param can't be null");
        }

        if (maxIdleTime > 0 && maxIdleUnit == null) {
            throw new NullPointerException("maxIdleUnit param can't be null");
        }

        long ttlTimeout = 0;
        if (ttl > 0) {
            ttlTimeout = System.currentTimeMillis() + ttlUnit.toMillis(ttl);
        }

        long maxIdleTimeout = 0;
        long maxIdleDelta = 0;
        if (maxIdleTime > 0) {
            maxIdleDelta = maxIdleUnit.toMillis(maxIdleTime);
            maxIdleTimeout = System.currentTimeMillis() + maxIdleDelta;
        }

        RFuture<Boolean> future = commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_BOOLEAN,
                "local insertable = false; " +
                        "local value = redis.call('hget', KEYS[1], ARGV[5]); " +
                        "if value == false then " +
                        "    insertable = true; " +
                        "else " +
                        "    if insertable == false then " +
                        "        local t, val = struct.unpack('dLc0', value); " +
                        "        local expireDate = 92233720368547758; " +
                        "        local expireDateScore = redis.call('zscore', KEYS[2], ARGV[5]); " +
                        "        if expireDateScore ~= false then " +
                        "            expireDate = tonumber(expireDateScore) " +
                        "        end; " +
                        "        if t ~= 0 then " +
                        "            local expireIdle = redis.call('zscore', KEYS[3], ARGV[5]); " +
                        "            if expireIdle ~= false then " +
                        "                expireDate = math.min(expireDate, tonumber(expireIdle)) " +
                        "            end; " +
                        "        end; " +
                        "        if expireDate <= tonumber(ARGV[1]) then " +
                        "            insertable = true; " +
                        "        end; " +
                        "    end; " +
                        "end; " +
                        "if insertable == true then " +
                             // ttl
                        "    if tonumber(ARGV[2]) > 0 then " +
                        "        redis.call('zadd', KEYS[2], ARGV[2], ARGV[5]); " +
                        "    else " +
                        "        redis.call('zrem', KEYS[2], ARGV[5]); " +
                        "    end; " +
                             // idle
                        "    if tonumber(ARGV[3]) > 0 then " +
                        "        redis.call('zadd', KEYS[3], ARGV[3], ARGV[5]); " +
                        "    else " +
                        "        redis.call('zrem', KEYS[3], ARGV[5]); " +
                        "    end; " +
                             // last access time
                        "    local maxSize = tonumber(redis.call('hget', KEYS[7], 'max-size')); " +
                        "    if maxSize ~= nil and maxSize ~= 0 then " +
                        "        local currentTime = tonumber(ARGV[1]); " +
                        "        local lastAccessTimeSetName = KEYS[5]; " +
                        "        redis.call('zadd', lastAccessTimeSetName, currentTime, ARGV[5]); " +
                        "        local cacheSize = tonumber(redis.call('hlen', KEYS[1])); " +
                        "        if cacheSize >= maxSize then " +
                        "            local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize); " +
                        "            for index, lruItem in ipairs(lruItems) do " +
                        "                if lruItem then " +
                        "                    local lruItemValue = redis.call('hget', KEYS[1], lruItem); " +
                        "                    redis.call('hdel', KEYS[1], lruItem); " +
                        "                    redis.call('zrem', KEYS[2], lruItem); " +
                        "                    redis.call('zrem', KEYS[3], lruItem); " +
                        "                    redis.call('zrem', lastAccessTimeSetName, lruItem); " +
                        "                    local removedChannelName = KEYS[6]; " +
                        "                    local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue); " +
                        "                    redis.call('publish', removedChannelName, msg); " +
                        "                end; " +
                        "            end; " +
                        "        end; " +
                        "    end; " +
                             // value
                        "    local val = struct.pack('dLc0', ARGV[4], string.len(ARGV[6]), ARGV[6]); " +
                        "    redis.call('hset', KEYS[1], ARGV[5], val); " +
                        "    local msg = struct.pack('Lc0Lc0', string.len(ARGV[5]), ARGV[5], string.len(ARGV[6]), ARGV[6]); " +
                        "    redis.call('publish', KEYS[4], msg); " +
                        "    return 1; " +
                        "else " +
                        "    return 0; " +
                        "end; ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getCreatedChannelNameByKey(key),
                        getLastAccessTimeSetNameByKey(key), getRemovedChannelNameByKey(key), getOptionsName(key)),
                System.currentTimeMillis(), ttlTimeout, maxIdleTimeout, maxIdleDelta, encodeMapKey(key), encodeMapValue(value));
        if (hasNoWriter()) {
            return future;
        }

        MapWriterTask<Boolean> listener = new MapWriterTask<Boolean>() {
            @Override
            protected void execute() {
                options.getWriter().write(key, value);
            }
            @Override
            protected boolean condition(Future<Boolean> future) {
                return future.getNow();
            }
        };
        return mapWriterFuture(future, listener);
    }

    @Override
    protected RFuture<Boolean> replaceOperationAsync(K key, V oldValue, V newValue) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_BOOLEAN,
            "local v = redis.call('hget', KEYS[1], ARGV[2]); " +
            "if v == false then " +
            "    return 0; " +
            "end; " +
            "local expireDate = 92233720368547758; " +
            "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); " +
            "if expireDateScore ~= false then " +
            "    expireDate = tonumber(expireDateScore) " +
            "end; " +
            "" +
            "local t, val = struct.unpack('dLc0', v); " +
            "if t ~= 0 then " +
            "    local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); " +
            "    if expireIdle ~= false then " +
            "        expireDate = math.min(expireDate, tonumber(expireIdle)) " +
            "    end; " +
            "end; " +
            "if expireDate > tonumber(ARGV[1]) and val == ARGV[3] then " +
            "    local msg = struct.pack('Lc0Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[4]), ARGV[4], string.len(ARGV[3]), ARGV[3]); " +
            "    redis.call('publish', KEYS[4], msg); " +
            "" +
            "    local value = struct.pack('dLc0', t, string.len(ARGV[4]), ARGV[4]); " +
            "    redis.call('hset', KEYS[1], ARGV[2], value); " +
            "    return 1; " +
            "end; " +
            "return 0; ",
            Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getUpdatedChannelNameByKey(key)),
            System.currentTimeMillis(), encodeMapKey(key), encodeMapValue(oldValue), encodeMapValue(newValue));
    }

    @Override
    protected RFuture<V> replaceOperationAsync(K key, V value) {
        return commandExecutor.evalWriteAsync(getName(key), codec, RedisCommands.EVAL_MAP_VALUE,
                "local value = redis.call('hget', KEYS[1], ARGV[2]); " +
                "if value == false then " +
                "    return nil; " +
                "end; " +
                "local t, val = struct.unpack('dLc0', value); " +
                "local expireDate = 92233720368547758; " +
                "local expireDateScore = redis.call('zscore', KEYS[2], ARGV[2]); " +
                "if expireDateScore ~= false then " +
                "    expireDate = tonumber(expireDateScore) " +
                "end; " +
                "if t ~= 0 then " +
                "    local expireIdle = redis.call('zscore', KEYS[3], ARGV[2]); " +
                "    if expireIdle ~= false then " +
                "        expireDate = math.min(expireDate, tonumber(expireIdle)) " +
                "    end; " +
                "end; " +
                "if expireDate <= tonumber(ARGV[1]) then " +
                "    return nil; " +
                "end; " +
                "local value = struct.pack('dLc0', t, string.len(ARGV[3]), ARGV[3]); " +
                "redis.call('hset', KEYS[1], ARGV[2], value); " +
                "local msg = struct.pack('Lc0Lc0Lc0', string.len(ARGV[2]), ARGV[2], string.len(ARGV[3]), ARGV[3], string.len(val), val); " +
                "redis.call('publish', KEYS[4], msg); " +
                "return val; ",
                Arrays.<Object>asList(getName(key), getTimeoutSetNameByKey(key), getIdleSetNameByKey(key), getUpdatedChannelNameByKey(key)),
                System.currentTimeMillis(), encodeMapKey(key), encodeMapValue(value));

    }

    @Override
    public RFuture<Void> putAllOperationAsync(Map<? extends K, ? extends V> map) {
        List<Object> params = new ArrayList<Object>(map.size()*2 + 1);
        params.add(System.currentTimeMillis());
        for (java.util.Map.Entry<? extends K, ? extends V> t : map.entrySet()) {
            if (t.getKey() == null) {
                throw new NullPointerException("map key can't be null");
            }
            if (t.getValue() == null) {
                throw new NullPointerException("map value can't be null");
            }

            params.add(encodeMapKey(t.getKey()));
            params.add(encodeMapValue(t.getValue()));
        }

        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_VOID,
                  "local currentTime = tonumber(table.remove(ARGV, 1)); " + // index is the first parameter
                  "local maxSize = tonumber(redis.call('hget', KEYS[8], 'max-size'));" +
                  "for i, value in ipairs(ARGV) do "
                    + "if i % 2 == 0 then " 
                      + "local key = ARGV[i-1];" +

                        "local v = redis.call('hget', KEYS[1], key);" +
                        "local exists = false;" +
                        "if v ~= false then" +
                        "    local t, val = struct.unpack('dLc0', v);" +
                        "    local expireDate = 92233720368547758;" +
                        "    local expireDateScore = redis.call('zscore', KEYS[2], key);" +
                        "    if expireDateScore ~= false then" +
                        "        expireDate = tonumber(expireDateScore)" +
                        "    end;" +
                        "    if t ~= 0 then" +
                        "        local expireIdle = redis.call('zscore', KEYS[3], key);" +
                        "        if expireIdle ~= false then" +
                        "            expireDate = math.min(expireDate, tonumber(expireIdle))" +
                        "        end;" +
                        "    end;" +
                        "    if expireDate > tonumber(currentTime) then" +
                        "        exists = true;" +
                        "    end;" +
                        "end;" +
                        "" +
                        "local newvalue = struct.pack('dLc0', 0, string.len(value), value);" +
                        "redis.call('hset', KEYS[1], key, newvalue);" +

                        "local lastAccessTimeSetName = KEYS[6];" +
                        "if exists == false then" +
                        "    if maxSize ~= nil and maxSize ~= 0 then" +
                        "        redis.call('zadd', lastAccessTimeSetName, currentTime, key);" +
                        "        local cacheSize = tonumber(redis.call('hlen', KEYS[1]));" +
                        "        if cacheSize > maxSize then" +
                        "            local lruItems = redis.call('zrange', lastAccessTimeSetName, 0, cacheSize - maxSize - 1);" +
                        "            for index, lruItem in ipairs(lruItems) do" +
                        "                if lruItem then" +
                        "                    local lruItemValue = redis.call('hget', KEYS[1], lruItem);" +
                        "                    redis.call('hdel', KEYS[1], lruItem);" +
                        "                    redis.call('zrem', KEYS[2], lruItem);" +
                        "                    redis.call('zrem', KEYS[3], lruItem);" +
                        "                    redis.call('zrem', lastAccessTimeSetName, lruItem);" +
                        "                    local removedChannelName = KEYS[7];" +
                        "                    local msg = struct.pack('Lc0Lc0', string.len(lruItem), lruItem, string.len(lruItemValue), lruItemValue);" +
                        "                    redis.call('publish', removedChannelName, msg);" +
                        "                end;" +
                        "            end" +
                        "        end;" +
                        "    end;" +
                        "    local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(value), value);" +
                        "    redis.call('publish', KEYS[4], msg);" +
                        "else " +
                            "local t, val = struct.unpack('dLc0', v);" +
                            "local msg = struct.pack('Lc0Lc0Lc0', string.len(key), key, string.len(value), value, string.len(val), val);" +
                            "redis.call('publish', KEYS[5], msg);" + 
                            
                        "    if maxSize ~= nil and maxSize ~= 0 then " +
                        "        redis.call('zadd', lastAccessTimeSetName, currentTime, key);" +
                        "    end;" +
                        "end;"
                    + "end;"
                + "end;",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getCreatedChannelName(),
                        getUpdatedChannelName(), getLastAccessTimeSetName(), getRemovedChannelName(), getOptionsName()),
            params.toArray());
    }

    private Boolean isWindows;
    
    @Override
    public int addListener(final MapEntryListener listener) {
        if (listener == null) {
            throw new NullPointerException();
        }
        
        if (isWindows == null) {
            RFuture<Map<String, String>> serverFuture = commandExecutor.readAsync((String)null, StringCodec.INSTANCE, RedisCommands.INFO_SERVER);
            serverFuture.syncUninterruptibly();
            String os = serverFuture.getNow().get("os");
            isWindows = os.contains("Windows");
        }

        if (listener instanceof EntryRemovedListener) {
            RTopic<List<Object>> topic = redisson.getTopic(getRemovedChannelName(), new MapCacheEventCodec(codec, isWindows));
            return topic.addListener(new MessageListener<List<Object>>() {
                @Override
                public void onMessage(String channel, List<Object> msg) {
                    EntryEvent<K, V> event = new EntryEvent<K, V>(RedissonMapCache.this, EntryEvent.Type.REMOVED, (K)msg.get(0), (V)msg.get(1), null);
                    ((EntryRemovedListener<K, V>) listener).onRemoved(event);
                }
            });
        }

        if (listener instanceof EntryCreatedListener) {
            RTopic<List<Object>> topic = redisson.getTopic(getCreatedChannelName(), new MapCacheEventCodec(codec, isWindows));
            return topic.addListener(new MessageListener<List<Object>>() {
                @Override
                public void onMessage(String channel, List<Object> msg) {
                    EntryEvent<K, V> event = new EntryEvent<K, V>(RedissonMapCache.this, EntryEvent.Type.CREATED, (K)msg.get(0), (V)msg.get(1), null);
                    ((EntryCreatedListener<K, V>) listener).onCreated(event);
                }
            });
        }

        if (listener instanceof EntryUpdatedListener) {
            RTopic<List<Object>> topic = redisson.getTopic(getUpdatedChannelName(), new MapCacheEventCodec(codec, isWindows));
            return topic.addListener(new MessageListener<List<Object>>() {
                @Override
                public void onMessage(String channel, List<Object> msg) {
                    EntryEvent<K, V> event = new EntryEvent<K, V>(RedissonMapCache.this, EntryEvent.Type.UPDATED, (K)msg.get(0), (V)msg.get(1), (V)msg.get(2));
                    ((EntryUpdatedListener<K, V>) listener).onUpdated(event);
                }
            });
        }

        if (listener instanceof EntryExpiredListener) {
            RTopic<List<Object>> topic = redisson.getTopic(getExpiredChannelName(), new MapCacheEventCodec(codec, isWindows));
            return topic.addListener(new MessageListener<List<Object>>() {
                @Override
                public void onMessage(String channel, List<Object> msg) {
                    EntryEvent<K, V> event = new EntryEvent<K, V>(RedissonMapCache.this, EntryEvent.Type.EXPIRED, (K)msg.get(0), (V)msg.get(1), null);
                    ((EntryExpiredListener<K, V>) listener).onExpired(event);
                }
            });
        }

        throw new IllegalArgumentException("Wrong listener type " + listener.getClass());
    }

    @Override
    public void removeListener(int listenerId) {
        RTopic<List<Object>> removedTopic = redisson.getTopic(getRemovedChannelName());
        removedTopic.removeListener(listenerId);

        RTopic<List<Object>> createdTopic = redisson.getTopic(getCreatedChannelName());
        createdTopic.removeListener(listenerId);

        RTopic<List<Object>> updatedTopic = redisson.getTopic(getUpdatedChannelName());
        updatedTopic.removeListener(listenerId);

        RTopic<List<Object>> expiredTopic = redisson.getTopic(getExpiredChannelName());
        expiredTopic.removeListener(listenerId);
    }

    @Override
    public RFuture<Boolean> deleteAsync() {
        return commandExecutor.writeAsync(getName(), RedisCommands.DEL_OBJECTS, 
                getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName());
    }

    @Override
    public RFuture<Boolean> expireAsync(long timeToLive, TimeUnit timeUnit) {
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); " +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    redis.call('pexpire', KEYS[5], ARGV[1]); " +
                        "    redis.call('zadd', KEYS[4], 92233720368547758, 'redisson__expiretag'); " +
                        "    redis.call('pexpire', KEYS[4], ARGV[1]); " +
                        "end; " +
                        "redis.call('zadd', KEYS[2], 92233720368547758, 'redisson__expiretag'); " +
                        "redis.call('pexpire', KEYS[2], ARGV[1]); " +
                        "redis.call('zadd', KEYS[3], 92233720368547758, 'redisson__expiretag'); " +
                        "redis.call('pexpire', KEYS[3], ARGV[1]); " +
                        "return redis.call('pexpire', KEYS[1], ARGV[1]); ",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()),
                timeUnit.toMillis(timeToLive));
    }

    @Override
    public RFuture<Boolean> expireAtAsync(long timestamp) {
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                        "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); " +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    redis.call('pexpire', KEYS[5], ARGV[1]); " +
                        "    redis.call('zadd', KEYS[4], 92233720368547758, 'redisson__expiretag'); " +
                        "    redis.call('pexpire', KEYS[4], ARGV[1]); " +
                        "end; " +
                        "redis.call('zadd', KEYS[2], 92233720368547758, 'redisson__expiretag'); " +
                        "redis.call('pexpireat', KEYS[2], ARGV[1]); " +
                        "redis.call('zadd', KEYS[3], 92233720368547758, 'redisson__expiretag'); " +
                        "redis.call('pexpire', KEYS[3], ARGV[1]); " +
                        "return redis.call('pexpireat', KEYS[1], ARGV[1]); ",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()),
                timestamp);
    }

    @Override
    public RFuture<Boolean> clearExpireAsync() {
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                        "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); " +
                        "if maxSize ~= nil and maxSize ~= 0 then " +
                        "    redis.call('persist', KEYS[5]); " +
                        "    redis.call('zrem', KEYS[4], 92233720368547758, 'redisson__expiretag'); " +
                        "    redis.call('persist', KEYS[4]); " +
                        "end; " +
                
                        "redis.call('zrem', KEYS[2], 'redisson__expiretag'); " +
                        "redis.call('persist', KEYS[2]); " +
                        "redis.call('zrem', KEYS[3], 'redisson__expiretag'); " +
                        "redis.call('persist', KEYS[3]); " +
                        "return redis.call('persist', KEYS[1]); ",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()));
    }

    @Override
    public RFuture<Set<K>> readAllKeySetAsync() {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_MAP_KEY_SET,
                "local s = redis.call('hgetall', KEYS[1]); " + 
                "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size'));"
                        + "local result = {}; "
                        + "for i, v in ipairs(s) do "
                            + "if i % 2 == 0 then "
                                + "local t, val = struct.unpack('dLc0', v); "
                                + "local key = s[i-1];" +
                                "local expireDate = 92233720368547758; " +
                                "local expireDateScore = redis.call('zscore', KEYS[2], key); "
                                + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                                + "end; "
                                + "if t ~= 0 then "
                                + "local expireIdle = redis.call('zscore', KEYS[3], key); "
                                + "if expireIdle ~= false then "
                                + "if tonumber(expireIdle) > tonumber(ARGV[1]) then "
                                    + "local value = struct.pack('dLc0', t, string.len(val), val); "
                                    + "redis.call('hset', KEYS[1], key, value); "
                                    + "redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), key); "
                                    + "if maxSize ~= nil and maxSize ~= 0 then "
                                    + "   redis.call('zadd', KEYS[4], tonumber(ARGV[1]), key); "
                                    + "end; "
                                + "end; "
                                + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                + "end; "
                                + "end; "
                                + "if expireDate > tonumber(ARGV[1]) then "
                                    + "table.insert(result, key); "
                                + "end; "
                            + "end; "
                        + "end;" +
                        "return result;",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()),
                System.currentTimeMillis());
    }

    @Override
    public RFuture<Set<java.util.Map.Entry<K, V>>> readAllEntrySetAsync() {
        return readAll(RedisCommands.EVAL_MAP_ENTRY);
    }

    private <R> RFuture<R> readAll(RedisCommand<?> evalCommandType) {
        return commandExecutor.evalWriteAsync(getName(), codec, evalCommandType,
                "local s = redis.call('hgetall', KEYS[1]); "
                        + "local result = {}; "
                        + "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size'));"
                        + "for i, v in ipairs(s) do "
                            + "if i % 2 == 0 then "
                                + "local t, val = struct.unpack('dLc0', v); "
                                + "local key = s[i-1];" +
                                "local expireDate = 92233720368547758; " +
                                "local expireDateScore = redis.call('zscore', KEYS[2], key); "
                                + "if expireDateScore ~= false then "
                                    + "expireDate = tonumber(expireDateScore) "
                                + "end; "
                                + "if t ~= 0 then "
                                    + "local expireIdle = redis.call('zscore', KEYS[3], key); "
                                    + "if expireIdle ~= false then "
                                        + "if tonumber(expireIdle) > tonumber(ARGV[1]) then "
                                            + "local value = struct.pack('dLc0', t, string.len(val), val); "
                                            + "redis.call('hset', KEYS[1], key, value); "
                                            + "redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), key); "
                                            + "if maxSize ~= nil and maxSize ~= 0 then "
                                            + "   redis.call('zadd', KEYS[4], tonumber(ARGV[1]), key); "
                                            + "end; "
                                        + "end; "
                                        + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                                    + "end; "
                                + "end; "
                                + "if expireDate > tonumber(ARGV[1]) then "
                                    + "table.insert(result, key); "
                                    + "table.insert(result, val); "
                                + "end; "
                            + "end; "
                        + "end;" +
                        "return result;",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()),
                System.currentTimeMillis());
    }

    @Override
    public RFuture<Map<K, V>> readAllMapAsync() {
        return readAll(RedisCommands.EVAL_MAP);
    }

    
    @Override
    public RFuture<Collection<V>> readAllValuesAsync() {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_MAP_VALUE_LIST,
                "local s = redis.call('hgetall', KEYS[1]); "
                    + "local result = {}; "
                    + "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); "
                    + "for i, v in ipairs(s) do "
                        + "if i % 2 == 0 then "
                            + "local t, val = struct.unpack('dLc0', v); "
                            + "local key = s[i-1];" +
                            "local expireDate = 92233720368547758; " +
                            "local expireDateScore = redis.call('zscore', KEYS[2], key); "
                            + "if expireDateScore ~= false then "
                                + "expireDate = tonumber(expireDateScore) "
                            + "end; "
                            + "if t ~= 0 then "
                            + "local expireIdle = redis.call('zscore', KEYS[3], key); "
                            + "if expireIdle ~= false then "
                                + "if tonumber(expireIdle) > tonumber(ARGV[1]) then "
                                    + "local value = struct.pack('dLc0', t, string.len(val), val); "
                                    + "redis.call('hset', KEYS[1], key, value); "
                                    + "redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), key); "
                                    + "if maxSize ~= nil and maxSize ~= 0 then "
                                    + "   redis.call('zadd', KEYS[4], tonumber(ARGV[1]), key); "
                                    + "end; "
                                + "end; "
                                + "expireDate = math.min(expireDate, tonumber(expireIdle)) "
                            + "end; "
                        + "end; "
                        + "if expireDate > tonumber(ARGV[1]) then "
                            + "table.insert(result, val); "
                        + "end; "
                        + "end; "
                    + "end;" +
                    "return result;",
                Arrays.<Object>asList(getName(), getTimeoutSetName(), getIdleSetName(), getLastAccessTimeSetName(), getOptionsName()),
                System.currentTimeMillis());
    }
}
