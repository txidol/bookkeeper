/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.benchmark;

import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.Assert;

import java.net.InetSocketAddress;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.util.LocalBookKeeper;
import org.apache.bookkeeper.test.BookKeeperClusterTestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.Arrays;
import java.util.List;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;

public class TestBenchmark extends BookKeeperClusterTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(TestBenchmark.class);

    public TestBenchmark() {
        super(5);
    }

    @Test
    public void testThroughputLatency() throws Exception {
        String latencyFile = System.getProperty("test.latency.file", "latencyDump.dat");
        BenchThroughputLatency.main(new String[] {
                "--zookeeper", zkUtil.getZooKeeperConnectString(),
                "--time", "10",
                "--skipwarmup",
                "--throttle", "1",
                "--sendlimit", "10000",
                "--latencyFile", latencyFile
            });
    }

    @Test
    public void testBookie() throws Exception {
        InetSocketAddress bookie = getBookie(0);
        BenchBookie.main(new String[] {
                "--host", bookie.getHostName(),
                "--port", String.valueOf(bookie.getPort()),
                "--zookeeper", zkUtil.getZooKeeperConnectString()
                });
    }

    @Test
    public void testReadThroughputLatency() throws Exception {
        final AtomicBoolean threwException = new AtomicBoolean(false);
        Thread t = new Thread() {
                public void run() {
                    try {
                        BenchReadThroughputLatency.main(new String[] {
                                "--zookeeper", zkUtil.getZooKeeperConnectString(),
                                "--listen", "10"});
                    } catch (Throwable t) {
                        LOG.error("Error reading", t);
                        threwException.set(true);
                    }
                }
            };
        t.start();

        Thread.sleep(10000);
        byte data[] = new byte[1024];
        Arrays.fill(data, (byte)'x');

        long lastLedgerId = 0;
        Assert.assertTrue("Thread should be running", t.isAlive());
        for (int i = 0; i < 10; i++) {
            BookKeeper bk = new BookKeeper(zkUtil.getZooKeeperConnectString());
            LedgerHandle lh = bk.createLedger(BookKeeper.DigestType.CRC32, "benchPasswd".getBytes());
            lastLedgerId = lh.getId();
            try {
                for (int j = 0; j < 100; j++) {
                    lh.addEntry(data);
                }
            } finally {
                lh.close();
                bk.close();
            }
        }
        for (int i = 0; i < 60; i++) {
            if (!t.isAlive()) {
                break;
            }
            Thread.sleep(1000); // wait for 10 seconds for reading to finish
        }

        Assert.assertFalse("Thread should be finished", t.isAlive());

        BenchReadThroughputLatency.main(new String[] {
                "--zookeeper", zkUtil.getZooKeeperConnectString(),
                "--ledger", String.valueOf(lastLedgerId)});

        final long nextLedgerId = lastLedgerId+1;
        t = new Thread() {
                public void run() {
                    try {
                        BenchReadThroughputLatency.main(new String[] {
                                "--zookeeper", zkUtil.getZooKeeperConnectString(),
                                "--ledger", String.valueOf(nextLedgerId)});
                    } catch (Throwable t) {
                        LOG.error("Error reading", t);
                        threwException.set(true);
                    }
                }
            };
        t.start();

        Assert.assertTrue("Thread should be running", t.isAlive());
        BookKeeper bk = new BookKeeper(zkUtil.getZooKeeperConnectString());
        LedgerHandle lh = bk.createLedger(BookKeeper.DigestType.CRC32, "benchPasswd".getBytes());
        try {
            for (int j = 0; j < 100; j++) {
                lh.addEntry(data);
            }
        } finally {
            lh.close();
            bk.close();
        }
        for (int i = 0; i < 60; i++) {
            if (!t.isAlive()) {
                break;
            }
            Thread.sleep(1000); // wait for 10 seconds for reading to finish
        }
        Assert.assertFalse("Thread should be finished", t.isAlive());
        Assert.assertFalse("A thread has thrown an exception, check logs", threwException.get());
    }
}
