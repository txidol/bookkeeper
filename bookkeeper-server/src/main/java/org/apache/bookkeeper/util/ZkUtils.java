/*
 *
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

package org.apache.bookkeeper.util;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.bookkeeper.zookeeper.ZooKeeperWatcherBase;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.ZooKeeper;

/**
 * Provided utilites for zookeeper access, etc.
 */
public class ZkUtils {

    /**
     * Create zookeeper path recursively
     *
     * @param zk
     *          Zookeeper client
     * @param originalPath
     *          Zookeeper full path
     * @param data
     *          Zookeeper data
     * @param acl
     *          Acl of the zk path
     * @param createMode
     *          Create mode of zk path
     * @param callback
     *          Callback
     * @param ctx
     *          Context object
     */
    public static void createFullPathOptimistic(
        final ZooKeeper zk, final String originalPath, final byte[] data,
        final List<ACL> acl, final CreateMode createMode,
        final AsyncCallback.StringCallback callback, final Object ctx) {

        zk.create(originalPath, data, acl, createMode, new StringCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx, String name) {

                if (rc != Code.NONODE.intValue()) {
                    callback.processResult(rc, path, ctx, name);
                    return;
                }

                // Since I got a nonode, it means that my parents don't exist
                // create mode is persistent since ephemeral nodes can't be
                // parents
                createFullPathOptimistic(zk, new File(originalPath).getParent().replace("\\", "/"), new byte[0], acl,
                        CreateMode.PERSISTENT, new StringCallback() {

                            @Override
                            public void processResult(int rc, String path, Object ctx, String name) {
                                if (rc == Code.OK.intValue() || rc == Code.NODEEXISTS.intValue()) {
                                    // succeeded in creating the parent, now
                                    // create the original path
                                    createFullPathOptimistic(zk, originalPath, data, acl, createMode, callback,
                                            ctx);
                                } else {
                                    callback.processResult(rc, path, ctx, name);
                                }
                            }
                        }, ctx);
            }
        }, ctx);

    }

    /**
     * Get new ZooKeeper client. Waits till the connection is complete. If
     * connection is not successful within timeout, then throws back exception.
     * 
     * @param servers
     *            ZK servers connection string.
     * @param timeout
     *            Session timeout.
     */
    public static ZooKeeper createConnectedZookeeperClient(String servers,
            ZooKeeperWatcherBase w) throws IOException, InterruptedException,
            KeeperException {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("servers cannot be empty");
        }
        final ZooKeeper newZk = new ZooKeeper(servers, w.getZkSessionTimeOut(),
                w);
        w.waitForConnection();
        // Re-checking zookeeper connection status
        if (!newZk.getState().isConnected()) {
            throw KeeperException.create(KeeperException.Code.CONNECTIONLOSS);
        }
        return newZk;
    }

    /**
     * Utility to create the complete znode path synchronously
     * 
     * @param zkc
     *            - ZK instance
     * @param path
     *            - znode path
     * @param data
     *            - znode data
     * @param acl
     *            - Acl of the zk path
     * @param createMode
     *            - Create mode of zk path
     * @throws KeeperException
     *             if the server returns a non-zero error code, or invalid ACL
     * @throws InterruptedException
     *             if the transaction is interrupted
     */
    public static void createFullPathOptimistic(ZooKeeper zkc, String path,
            byte[] data, final List<ACL> acl, final CreateMode createMode)
            throws KeeperException, InterruptedException {
        try {
            zkc.create(path, data, acl, createMode);
        } catch (KeeperException.NoNodeException nne) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash <= 0) {
                throw nne;
            }
            String parent = path.substring(0, lastSlash);
            createFullPathOptimistic(zkc, parent, new byte[0], acl, createMode);
            zkc.create(path, data, acl, createMode);
        }
    }
}
