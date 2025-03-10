/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.engine;

import org.apache.lucene.tests.util.RamUsageTester;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.opensearch.common.lease.Releasable;
import org.opensearch.index.translog.Translog;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;

public class LiveVersionMapTests extends OpenSearchTestCase {

    public void testRamBytesUsed() throws Exception {
        LiveVersionMap map = new LiveVersionMap();
        for (int i = 0; i < 10000; ++i) {
            BytesRefBuilder uid = new BytesRefBuilder();
            uid.copyChars(TestUtil.randomSimpleString(random(), 10, 20));
            try (Releasable r = map.acquireLock(uid.toBytesRef())) {
                map.putIndexUnderLock(uid.toBytesRef(), randomIndexVersionValue());
            }
        }
        long actualRamBytesUsed = RamUsageTester.ramUsed(map);
        long estimatedRamBytesUsed = map.ramBytesUsed();
        // less than 50% off
        assertEquals(actualRamBytesUsed, estimatedRamBytesUsed, actualRamBytesUsed / 2);

        // now refresh
        map.beforeRefresh();
        map.afterRefresh(true);

        for (int i = 0; i < 10000; ++i) {
            BytesRefBuilder uid = new BytesRefBuilder();
            uid.copyChars(TestUtil.randomSimpleString(random(), 10, 20));
            try (Releasable r = map.acquireLock(uid.toBytesRef())) {
                map.putIndexUnderLock(uid.toBytesRef(), randomIndexVersionValue());
            }
        }
        actualRamBytesUsed = RamUsageTester.ramUsed(map);
        estimatedRamBytesUsed = map.ramBytesUsed();
        // With Java 9, RamUsageTester computes the memory usage of maps as
        // the memory usage of an array that would contain exactly all keys
        // and values. This is an under-estimation of the actual memory
        // usage since it ignores the impact of the load factor and of the
        // linked list/tree that is used to resolve collisions. So we use a
        // bigger tolerance.
        // less than 50% off
        long tolerance = actualRamBytesUsed / 2;
        assertEquals(actualRamBytesUsed, estimatedRamBytesUsed, tolerance);
    }

    public void testRefreshingBytes() throws IOException {
        LiveVersionMap map = new LiveVersionMap();
        BytesRefBuilder uid = new BytesRefBuilder();
        uid.copyChars(TestUtil.randomSimpleString(random(), 10, 20));
        try (Releasable r = map.acquireLock(uid.toBytesRef())) {
            map.putIndexUnderLock(uid.toBytesRef(), randomIndexVersionValue());
        }
        map.beforeRefresh();
        assertThat(map.getRefreshingBytes(), greaterThan(0L));
        map.afterRefresh(true);
        assertThat(map.getRefreshingBytes(), equalTo(0L));
    }

    private BytesRef uid(String string) {
        BytesRefBuilder builder = new BytesRefBuilder();
        builder.copyChars(string);
        // length of the array must be the same as the len of the ref... there is an assertion in LiveVersionMap#putUnderLock
        return BytesRef.deepCopyOf(builder.get());
    }

    public void testBasics() throws IOException {
        LiveVersionMap map = new LiveVersionMap();
        try (Releasable r = map.acquireLock(uid("test"))) {
            Translog.Location tlogLoc = randomTranslogLocation();
            map.putIndexUnderLock(uid("test"), new IndexVersionValue(tlogLoc, 1, 1, 1));
            assertEquals(new IndexVersionValue(tlogLoc, 1, 1, 1), map.getUnderLock(uid("test")));
            map.beforeRefresh();
            assertEquals(new IndexVersionValue(tlogLoc, 1, 1, 1), map.getUnderLock(uid("test")));
            map.afterRefresh(randomBoolean());
            assertNull(map.getUnderLock(uid("test")));

            map.putDeleteUnderLock(uid("test"), new DeleteVersionValue(1, 1, 1, 1));
            assertEquals(new DeleteVersionValue(1, 1, 1, 1), map.getUnderLock(uid("test")));
            map.beforeRefresh();
            assertEquals(new DeleteVersionValue(1, 1, 1, 1), map.getUnderLock(uid("test")));
            map.afterRefresh(randomBoolean());
            assertEquals(new DeleteVersionValue(1, 1, 1, 1), map.getUnderLock(uid("test")));
            map.pruneTombstones(2, 0);
            assertEquals(new DeleteVersionValue(1, 1, 1, 1), map.getUnderLock(uid("test")));
            map.pruneTombstones(2, 1);
            assertNull(map.getUnderLock(uid("test")));
        }
    }

    public void testConcurrently() throws IOException, InterruptedException {
        HashSet<BytesRef> keySet = new HashSet<>();
        int numKeys = randomIntBetween(50, 200);
        for (int i = 0; i < numKeys; i++) {
            keySet.add(uid(TestUtil.randomSimpleString(random(), 10, 20)));
        }
        List<BytesRef> keyList = new ArrayList<>(keySet);
        ConcurrentHashMap<BytesRef, VersionValue> values = new ConcurrentHashMap<>();
        ConcurrentHashMap<BytesRef, DeleteVersionValue> deletes = new ConcurrentHashMap<>();
        LiveVersionMap map = new LiveVersionMap();
        int numThreads = randomIntBetween(2, 5);

        Thread[] threads = new Thread[numThreads];
        CountDownLatch startGun = new CountDownLatch(numThreads);
        CountDownLatch done = new CountDownLatch(numThreads);
        int randomValuesPerThread = randomIntBetween(5000, 20000);
        final AtomicLong clock = new AtomicLong(0);
        final AtomicLong lastPrunedTimestamp = new AtomicLong(-1);
        final AtomicLong maxSeqNo = new AtomicLong();
        final AtomicLong lastPrunedSeqNo = new AtomicLong();
        for (int j = 0; j < threads.length; j++) {
            threads[j] = new Thread(() -> {
                startGun.countDown();
                try {
                    startGun.await();
                } catch (InterruptedException e) {
                    done.countDown();
                    throw new AssertionError(e);
                }
                try {
                    for (int i = 0; i < randomValuesPerThread; ++i) {
                        BytesRef bytesRef = randomFrom(random(), keyList);
                        try (Releasable r = map.acquireLock(bytesRef)) {
                            VersionValue versionValue = values.computeIfAbsent(
                                bytesRef,
                                v -> new IndexVersionValue(randomTranslogLocation(), randomLong(), maxSeqNo.incrementAndGet(), randomLong())
                            );
                            boolean isDelete = versionValue instanceof DeleteVersionValue;
                            if (isDelete) {
                                map.removeTombstoneUnderLock(bytesRef);
                                deletes.remove(bytesRef);
                            }
                            if (isDelete == false && rarely()) {
                                versionValue = new DeleteVersionValue(
                                    versionValue.version + 1,
                                    maxSeqNo.incrementAndGet(),
                                    versionValue.term,
                                    clock.getAndIncrement()
                                );
                                deletes.put(bytesRef, (DeleteVersionValue) versionValue);
                                map.putDeleteUnderLock(bytesRef, (DeleteVersionValue) versionValue);
                            } else {
                                versionValue = new IndexVersionValue(
                                    randomTranslogLocation(),
                                    versionValue.version + 1,
                                    maxSeqNo.incrementAndGet(),
                                    versionValue.term
                                );
                                map.putIndexUnderLock(bytesRef, (IndexVersionValue) versionValue);
                            }
                            values.put(bytesRef, versionValue);
                        }
                        if (rarely()) {
                            final long pruneSeqNo = randomLongBetween(0, maxSeqNo.get());
                            final long clockTick = randomLongBetween(0, clock.get());
                            map.pruneTombstones(clockTick, pruneSeqNo);
                            // make sure we track the latest timestamp and seqno we pruned the deletes
                            lastPrunedTimestamp.updateAndGet(prev -> Math.max(clockTick, prev));
                            lastPrunedSeqNo.updateAndGet(prev -> Math.max(pruneSeqNo, prev));
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
            threads[j].start();
        }
        do {
            final Map<BytesRef, VersionValue> valueMap = new HashMap<>(map.getAllCurrent());
            map.beforeRefresh();
            valueMap.forEach((k, v) -> {
                try (Releasable r = map.acquireLock(k)) {
                    VersionValue actualValue = map.getUnderLock(k);
                    assertNotNull(actualValue);
                    assertTrue(v.version <= actualValue.version);
                }
            });
            map.afterRefresh(randomBoolean());
            valueMap.forEach((k, v) -> {
                try (Releasable r = map.acquireLock(k)) {
                    VersionValue actualValue = map.getUnderLock(k);
                    if (actualValue != null) {
                        if (actualValue instanceof DeleteVersionValue) {
                            assertTrue(v.version <= actualValue.version); // deletes can be the same version
                        } else {
                            assertTrue(v.version < actualValue.version);
                        }

                    }
                }
            });
            if (randomBoolean()) {
                Thread.yield();
            }
        } while (done.getCount() != 0);

        for (int j = 0; j < threads.length; j++) {
            threads[j].join();
        }
        map.getAllCurrent().forEach((k, v) -> {
            VersionValue versionValue = values.get(k);
            assertNotNull(versionValue);
            assertEquals(v, versionValue);
        });
        Runnable assertTombstones = () -> map.getAllTombstones().entrySet().forEach(e -> {
            VersionValue versionValue = values.get(e.getKey());
            assertNotNull(versionValue);
            assertEquals(e.getValue(), versionValue);
            assertTrue(versionValue instanceof DeleteVersionValue);
        });
        assertTombstones.run();
        map.beforeRefresh();
        assertTombstones.run();
        map.afterRefresh(false);
        assertTombstones.run();

        deletes.entrySet().forEach(e -> {
            try (Releasable r = map.acquireLock(e.getKey())) {
                VersionValue value = map.getUnderLock(e.getKey());
                // here we keep track of the deletes and ensure that all deletes that are not visible anymore ie. not in the map
                // have a timestamp that is smaller or equal to the maximum timestamp that we pruned on
                final DeleteVersionValue delete = e.getValue();
                if (value == null) {
                    assertTrue(
                        delete.time + " > " + lastPrunedTimestamp.get() + "," + delete.seqNo + " > " + lastPrunedSeqNo.get(),
                        delete.time <= lastPrunedTimestamp.get() && delete.seqNo <= lastPrunedSeqNo.get()
                    );
                } else {
                    assertEquals(value, delete);
                }
            }
        });
        map.pruneTombstones(clock.incrementAndGet(), maxSeqNo.get());
        assertThat(map.getAllTombstones().entrySet(), empty());
    }

    public void testCarryOnSafeAccess() throws IOException {
        LiveVersionMap map = new LiveVersionMap();
        assertFalse(map.isUnsafe());
        assertFalse(map.isSafeAccessRequired());
        map.enforceSafeAccess();
        assertTrue(map.isSafeAccessRequired());
        assertFalse(map.isUnsafe());
        int numIters = randomIntBetween(1, 5);
        for (int i = 0; i < numIters; i++) { // if we don't do anything ie. no adds etc we will stay with the safe access required
            map.beforeRefresh();
            map.afterRefresh(randomBoolean());
            assertTrue("failed in iter: " + i, map.isSafeAccessRequired());
        }

        try (Releasable r = map.acquireLock(uid(""))) {
            map.maybePutIndexUnderLock(new BytesRef(""), randomIndexVersionValue());
        }
        assertFalse(map.isUnsafe());
        assertEquals(1, map.getAllCurrent().size());

        map.beforeRefresh();
        map.afterRefresh(randomBoolean());
        assertFalse(map.isUnsafe());
        assertFalse(map.isSafeAccessRequired());
        try (Releasable r = map.acquireLock(uid(""))) {
            map.maybePutIndexUnderLock(new BytesRef(""), randomIndexVersionValue());
        }
        assertTrue(map.isUnsafe());
        assertFalse(map.isSafeAccessRequired());
        assertEquals(0, map.getAllCurrent().size());
    }

    public void testRefreshTransition() throws IOException {
        LiveVersionMap map = new LiveVersionMap();
        try (Releasable r = map.acquireLock(uid("1"))) {
            map.maybePutIndexUnderLock(uid("1"), randomIndexVersionValue());
            assertTrue(map.isUnsafe());
            assertNull(map.getUnderLock(uid("1")));
            map.beforeRefresh();
            assertTrue(map.isUnsafe());
            assertNull(map.getUnderLock(uid("1")));
            map.afterRefresh(randomBoolean());
            assertNull(map.getUnderLock(uid("1")));
            assertFalse(map.isUnsafe());

            map.enforceSafeAccess();
            map.maybePutIndexUnderLock(uid("1"), randomIndexVersionValue());
            assertFalse(map.isUnsafe());
            assertNotNull(map.getUnderLock(uid("1")));
            map.beforeRefresh();
            assertFalse(map.isUnsafe());
            assertTrue(map.isSafeAccessRequired());
            assertNotNull(map.getUnderLock(uid("1")));
            map.afterRefresh(randomBoolean());
            assertNull(map.getUnderLock(uid("1")));
            assertFalse(map.isUnsafe());
            assertTrue(map.isSafeAccessRequired());
        }
    }

    public void testAddAndDeleteRefreshConcurrently() throws IOException, InterruptedException {
        LiveVersionMap map = new LiveVersionMap();
        int numIters = randomIntBetween(1000, 5000);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicLong version = new AtomicLong();
        CountDownLatch start = new CountDownLatch(2);
        BytesRef uid = uid("1");
        VersionValue initialVersion;
        try (Releasable ignore = map.acquireLock(uid)) {
            initialVersion = new IndexVersionValue(randomTranslogLocation(), version.incrementAndGet(), 1, 1);
            map.putIndexUnderLock(uid, (IndexVersionValue) initialVersion);
        }
        Thread t = new Thread(() -> {
            start.countDown();
            try {
                start.await();
                VersionValue nextVersionValue = initialVersion;
                for (int i = 0; i < numIters; i++) {
                    try (Releasable ignore = map.acquireLock(uid)) {
                        VersionValue underLock = map.getUnderLock(uid);
                        if (underLock != null) {
                            assertEquals(underLock, nextVersionValue);
                        } else {
                            underLock = nextVersionValue;
                        }
                        if (underLock.isDelete() || randomBoolean()) {
                            nextVersionValue = new IndexVersionValue(randomTranslogLocation(), version.incrementAndGet(), 1, 1);
                            map.putIndexUnderLock(uid, (IndexVersionValue) nextVersionValue);
                        } else {
                            nextVersionValue = new DeleteVersionValue(version.incrementAndGet(), 1, 1, 0);
                            map.putDeleteUnderLock(uid, (DeleteVersionValue) nextVersionValue);
                        }
                    }
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                done.set(true);
            }
        });
        t.start();
        start.countDown();
        while (done.get() == false) {
            map.beforeRefresh();
            Thread.yield();
            map.afterRefresh(false);
        }
        t.join();

        try (Releasable ignore = map.acquireLock(uid)) {
            VersionValue underLock = map.getUnderLock(uid);
            if (underLock != null) {
                assertEquals(version.get(), underLock.version);
            }
        }
    }

    public void testPruneTombstonesWhileLocked() throws InterruptedException, IOException {
        LiveVersionMap map = new LiveVersionMap();
        BytesRef uid = uid("1");

        try (Releasable ignore = map.acquireLock(uid)) {
            map.putDeleteUnderLock(uid, new DeleteVersionValue(0, 0, 0, 0));
            map.beforeRefresh(); // refresh otherwise we won't prune since it's tracked by the current map
            map.afterRefresh(false);
            Thread thread = new Thread(() -> { map.pruneTombstones(Long.MAX_VALUE, 0); });
            thread.start();
            thread.join();
            assertEquals(1, map.getAllTombstones().size());
        }
        Thread thread = new Thread(() -> { map.pruneTombstones(Long.MAX_VALUE, 0); });
        thread.start();
        thread.join();
        assertEquals(0, map.getAllTombstones().size());
    }

    public void testRandomlyIndexDeleteAndRefresh() throws Exception {
        final LiveVersionMap versionMap = new LiveVersionMap();
        final BytesRef uid = uid("1");
        final long versions = between(10, 1000);
        VersionValue latestVersion = null;
        for (long i = 0; i < versions; i++) {
            if (randomBoolean()) {
                versionMap.beforeRefresh();
                versionMap.afterRefresh(randomBoolean());
            }
            if (randomBoolean()) {
                versionMap.enforceSafeAccess();
            }
            try (Releasable ignore = versionMap.acquireLock(uid)) {
                if (randomBoolean()) {
                    latestVersion = new DeleteVersionValue(randomNonNegativeLong(), randomLong(), randomLong(), randomLong());
                    versionMap.putDeleteUnderLock(uid, (DeleteVersionValue) latestVersion);
                    assertThat(versionMap.getUnderLock(uid), equalTo(latestVersion));
                } else if (randomBoolean()) {
                    latestVersion = new IndexVersionValue(randomTranslogLocation(), randomNonNegativeLong(), randomLong(), randomLong());
                    versionMap.maybePutIndexUnderLock(uid, (IndexVersionValue) latestVersion);
                    if (versionMap.isSafeAccessRequired()) {
                        assertThat(versionMap.getUnderLock(uid), equalTo(latestVersion));
                    } else {
                        assertThat(versionMap.getUnderLock(uid), nullValue());
                    }
                }
                if (versionMap.getUnderLock(uid) != null) {
                    assertThat(versionMap.getUnderLock(uid), equalTo(latestVersion));
                }
            }
        }
    }

    IndexVersionValue randomIndexVersionValue() {
        return new IndexVersionValue(randomTranslogLocation(), randomNonNegativeLong(), randomNonNegativeLong(), randomNonNegativeLong());
    }

    Translog.Location randomTranslogLocation() {
        if (randomBoolean()) {
            return null;
        } else {
            return new Translog.Location(randomNonNegativeLong(), randomNonNegativeLong(), randomInt());
        }
    }
}
