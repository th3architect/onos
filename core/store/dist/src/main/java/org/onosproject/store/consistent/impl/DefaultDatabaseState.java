/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.store.consistent.impl;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.onosproject.store.service.DatabaseUpdate;
import org.onosproject.store.service.Transaction;
import org.onosproject.store.service.Versioned;
import org.onosproject.store.service.DatabaseUpdate.Type;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import net.kuujo.copycat.state.Initializer;
import net.kuujo.copycat.state.StateContext;

/**
 * Default database state.
 */
public class DefaultDatabaseState implements DatabaseState<String, byte[]> {
    private Long nextVersion;
    private Map<String, AtomicLong> counters;
    private Map<String, Map<String, Versioned<byte[]>>> tables;

    /**
     * This locks map has a structure similar to the "tables" map above and
     * holds all the provisional updates made during a transaction's prepare phase.
     * The entry value is represented as the tuple: (transactionId, newValue)
     * If newValue == null that signifies this update is attempting to
     * delete the existing value.
     * This map also serves as a lock on the entries that are being updated.
     * The presence of a entry in this map indicates that element is
     * participating in a transaction and is currently locked for updates.
     */
    private Map<String, Map<String, Pair<Long, byte[]>>> locks;

    @Initializer
    @Override
    public void init(StateContext<DatabaseState<String, byte[]>> context) {
        counters = context.get("counters");
        if (counters == null) {
            counters = Maps.newConcurrentMap();
            context.put("counters", counters);
        }
        tables = context.get("tables");
        if (tables == null) {
            tables = Maps.newConcurrentMap();
            context.put("tables", tables);
        }
        locks = context.get("locks");
        if (locks == null) {
            locks = Maps.newConcurrentMap();
            context.put("locks", locks);
        }
        nextVersion = context.get("nextVersion");
        if (nextVersion == null) {
            nextVersion = new Long(0);
            context.put("nextVersion", nextVersion);
        }
    }

    @Override
    public Set<String> tableNames() {
        return new HashSet<>(tables.keySet());
    }

    @Override
    public Map<String, Long> counters() {
        Map<String, Long> counterMap = Maps.newHashMap();
        counters.forEach((k, v) -> counterMap.put(k, v.get()));
        return counterMap;
    }

    @Override
    public int size(String tableName) {
      return getTableMap(tableName).size();
    }

    @Override
    public boolean isEmpty(String tableName) {
        return getTableMap(tableName).isEmpty();
    }

    @Override
    public boolean containsKey(String tableName, String key) {
        return getTableMap(tableName).containsKey(key);
    }

    @Override
    public boolean containsValue(String tableName, byte[] value) {
        return getTableMap(tableName).values().stream().anyMatch(v -> Arrays.equals(v.value(), value));
    }

    @Override
    public Versioned<byte[]> get(String tableName, String key) {
        return getTableMap(tableName).get(key);
    }

    @Override
    public Result<Versioned<byte[]>> put(String tableName, String key, byte[] value) {
        return isLockedForUpdates(tableName, key)
                ? Result.locked()
                : Result.ok(getTableMap(tableName).put(key, new Versioned<>(value, ++nextVersion)));
    }

    @Override
    public Result<Versioned<byte[]>> remove(String tableName, String key) {
        return isLockedForUpdates(tableName, key)
                ? Result.locked()
                : Result.ok(getTableMap(tableName).remove(key));
    }

    @Override
    public Result<Void> clear(String tableName) {
        if (areTransactionsInProgress(tableName)) {
            return Result.locked();
        }
        getTableMap(tableName).clear();
        return Result.ok(null);
    }

    @Override
    public Set<String> keySet(String tableName) {
        return ImmutableSet.copyOf(getTableMap(tableName).keySet());
    }

    @Override
    public Collection<Versioned<byte[]>> values(String tableName) {
        return ImmutableList.copyOf(getTableMap(tableName).values());
    }

    @Override
    public Set<Entry<String, Versioned<byte[]>>> entrySet(String tableName) {
        return ImmutableSet.copyOf(getTableMap(tableName)
                .entrySet()
                .stream()
                .map(entry -> Pair.of(entry.getKey(), entry.getValue()))
                .collect(Collectors.toSet()));
    }

    @Override
    public Result<Versioned<byte[]>> putIfAbsent(String tableName, String key, byte[] value) {
        if (isLockedForUpdates(tableName, key)) {
            return Result.locked();
        }
        Versioned<byte[]> existingValue = get(tableName, key);
        Versioned<byte[]> currentValue = existingValue != null ? existingValue : put(tableName, key, value).value();
        return Result.ok(currentValue);
    }

    @Override
    public Result<Boolean> remove(String tableName, String key, byte[] value) {
        if (isLockedForUpdates(tableName, key)) {
            return Result.locked();
        }
        Versioned<byte[]> existing = get(tableName, key);
        if (existing != null && Arrays.equals(existing.value(), value)) {
            getTableMap(tableName).remove(key);
            return Result.ok(true);
        }
        return Result.ok(false);
    }

    @Override
    public Result<Boolean> remove(String tableName, String key, long version) {
        if (isLockedForUpdates(tableName, key)) {
            return Result.locked();
        }
        Versioned<byte[]> existing = get(tableName, key);
        if (existing != null && existing.version() == version) {
            remove(tableName, key);
            return Result.ok(true);
        }
        return Result.ok(false);
    }

    @Override
    public Result<Boolean> replace(String tableName, String key, byte[] oldValue, byte[] newValue) {
        if (isLockedForUpdates(tableName, key)) {
            return Result.locked();
        }
        Versioned<byte[]> existing = get(tableName, key);
        if (existing != null && Arrays.equals(existing.value(), oldValue)) {
            put(tableName, key, newValue);
            return Result.ok(true);
        }
        return Result.ok(false);
    }

    @Override
    public Result<Boolean> replace(String tableName, String key, long oldVersion, byte[] newValue) {
        if (isLockedForUpdates(tableName, key)) {
            return Result.locked();
        }
        Versioned<byte[]> existing = get(tableName, key);
        if (existing != null && existing.version() == oldVersion) {
            put(tableName, key, newValue);
            return Result.ok(true);
        }
        return Result.ok(false);
    }

    @Override
    public Long nextValue(String counterName) {
        return getCounter(counterName).incrementAndGet();
    }

    @Override
    public Long currentValue(String counterName) {
        return getCounter(counterName).get();
    }

    @Override
    public boolean prepareAndCommit(Transaction transaction) {
        if (prepare(transaction)) {
            return commit(transaction);
        }
        return false;
    }

    @Override
    public boolean prepare(Transaction transaction) {
        if (transaction.updates().stream().anyMatch(update ->
                    isLockedByAnotherTransaction(update.tableName(),
                                                 update.key(),
                                                 transaction.id()))) {
            return false;
        }

        if (transaction.updates().stream().allMatch(this::isUpdatePossible)) {
            transaction.updates().forEach(update -> doProvisionalUpdate(update, transaction.id()));
            return true;
        }
        return false;
    }

    @Override
    public boolean commit(Transaction transaction) {
        transaction.updates().forEach(update -> commitProvisionalUpdate(update, transaction.id()));
        return true;
    }

    @Override
    public boolean rollback(Transaction transaction) {
        transaction.updates().forEach(update -> undoProvisionalUpdate(update, transaction.id()));
        return true;
    }

    private Map<String, Versioned<byte[]>> getTableMap(String tableName) {
        return tables.computeIfAbsent(tableName, name -> Maps.newConcurrentMap());
    }

    private Map<String, Pair<Long, byte[]>> getLockMap(String tableName) {
        return locks.computeIfAbsent(tableName, name -> Maps.newConcurrentMap());
    }

    private AtomicLong getCounter(String counterName) {
        return counters.computeIfAbsent(counterName, name -> new AtomicLong(0));
    }

    private boolean isUpdatePossible(DatabaseUpdate update) {
        Versioned<byte[]> existingEntry = get(update.tableName(), update.key());
        switch (update.type()) {
        case PUT:
        case REMOVE:
            return true;
        case PUT_IF_ABSENT:
            return existingEntry == null;
        case PUT_IF_VERSION_MATCH:
            return existingEntry != null && existingEntry.version() == update.currentVersion();
        case PUT_IF_VALUE_MATCH:
            return existingEntry != null && Arrays.equals(existingEntry.value(), update.currentValue());
        case REMOVE_IF_VERSION_MATCH:
            return existingEntry == null || existingEntry.version() == update.currentVersion();
        case REMOVE_IF_VALUE_MATCH:
            return existingEntry == null || Arrays.equals(existingEntry.value(), update.currentValue());
        default:
            throw new IllegalStateException("Unsupported type: " + update.type());
        }
    }

    private void doProvisionalUpdate(DatabaseUpdate update, long transactionId) {
        Map<String, Pair<Long, byte[]>> lockMap = getLockMap(update.tableName());
        switch (update.type()) {
        case PUT:
        case PUT_IF_ABSENT:
        case PUT_IF_VERSION_MATCH:
        case PUT_IF_VALUE_MATCH:
            lockMap.put(update.key(), Pair.of(transactionId, update.value()));
            break;
        case REMOVE:
        case REMOVE_IF_VERSION_MATCH:
        case REMOVE_IF_VALUE_MATCH:
            lockMap.put(update.key(), null);
            break;
        default:
            throw new IllegalStateException("Unsupported type: " + update.type());
        }
    }

    private void commitProvisionalUpdate(DatabaseUpdate update, long transactionId) {
        String tableName = update.tableName();
        String key = update.key();
        Type type = update.type();
        Pair<Long, byte[]> provisionalUpdate = getLockMap(tableName).get(key);
        if (Objects.equal(transactionId, provisionalUpdate.getLeft()))  {
            getLockMap(tableName).remove(key);
        } else {
            return;
        }

        switch (type) {
        case PUT:
        case PUT_IF_ABSENT:
        case PUT_IF_VERSION_MATCH:
        case PUT_IF_VALUE_MATCH:
            put(tableName, key, provisionalUpdate.getRight());
            break;
        case REMOVE:
        case REMOVE_IF_VERSION_MATCH:
        case REMOVE_IF_VALUE_MATCH:
            remove(tableName, key);
            break;
        default:
            break;
        }
    }

    private void undoProvisionalUpdate(DatabaseUpdate update, long transactionId) {
        String tableName = update.tableName();
        String key = update.key();
        Pair<Long, byte[]> provisionalUpdate = getLockMap(tableName).get(key);
        if (provisionalUpdate == null) {
            return;
        }
        if (Objects.equal(transactionId, provisionalUpdate.getLeft()))  {
            getLockMap(tableName).remove(key);
        }
    }

    private boolean isLockedByAnotherTransaction(String tableName, String key, long transactionId) {
        Pair<Long, byte[]> update = getLockMap(tableName).get(key);
        return update != null && !Objects.equal(transactionId, update.getLeft());
    }

    private boolean isLockedForUpdates(String tableName, String key) {
        return getLockMap(tableName).containsKey(key);
    }

    private boolean areTransactionsInProgress(String tableName) {
        return !getLockMap(tableName).isEmpty();
    }
}
