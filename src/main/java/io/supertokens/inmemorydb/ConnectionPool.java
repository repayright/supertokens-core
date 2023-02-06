/*
 *    Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.inmemorydb;

import io.supertokens.ResourceDistributor;
import io.supertokens.exceptions.TenantOrAppNotFoundException;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConnectionPool extends ResourceDistributor.SingletonResource {

    private static final String RESOURCE_KEY = "io.supertokens.inmemorydb.ConnectionPool";
    private static String URL = "jdbc:sqlite:file::memory:?cache=shared";

    // we use this to keep all the information in memory across requests.
    private Connection alwaysAlive = null;
    private Lock lock = new Lock();

    public ConnectionPool() throws SQLException {
        this.alwaysAlive = DriverManager.getConnection(URL);
    }

    static void initPool(Start start) throws SQLException {
        start.getResourceDistributor()
                .setResource(new TenantIdentifier(null, null, null), RESOURCE_KEY, new ConnectionPool());
    }

    public static Connection getConnection(Start start) throws SQLException {
        if (!start.enabled) {
            throw new SQLException("Storage layer disabled");
        }
        return new ConnectionWithLocks(DriverManager.getConnection(URL), ConnectionPool.getInstance(start));
    }

    private static ConnectionPool getInstance(Start start) {
        try {
            return (ConnectionPool) start.getResourceDistributor()
                    .getResource(new TenantIdentifier(null, null, null), RESOURCE_KEY);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException("Should never come here");
        }
    }

    static void close(Start start) {
        if (getInstance(start) == null) {
            return;
        }
        try {
            getInstance(start).alwaysAlive.close();
        } catch (Exception ignored) {
        }
    }

    public void lock(String key) {
        this.lock.lock(key);
    }

    public void unlock(String key) {
        this.lock.unlock(key);
    }

}
