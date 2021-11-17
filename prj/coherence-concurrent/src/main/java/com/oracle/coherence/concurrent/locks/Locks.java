/*
 * Copyright (c) 2021 Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.concurrent.locks;

import com.oracle.coherence.common.collections.ConcurrentHashMap;

import com.oracle.coherence.concurrent.config.ConcurrentServicesSessionConfiguration;
import com.oracle.coherence.concurrent.locks.internal.ExclusiveLockHolder;

import com.tangosol.net.Coherence;
import com.tangosol.net.NamedMap;
import com.tangosol.net.Session;

import java.util.Map;

/**
 * Factory methods for various distributed lock implementations.
 *
 * @author Aleks Seovic  2021.10.20
 * @since 21.12
 */
public class Locks
    {
    /**
     * Return a singleton instance of an exclusive lock with a specified name.
     *
     * @param sName  the cluster-wide, unique name of the exclusive lock
     *
     * @return an instance of an exclusive lock with a specified name
     */
    public static DistributedLock exclusive(String sName)
        {
        return f_mapExclusive.computeIfAbsent(sName, n -> new DistributedLock(n, exclusiveLocksMap()));
        }

    /*
     * Return a singleton instance of a read/write lock with a specified name.
     *
     * @param sName  the cluster-wide, unique name of the read/write lock
     *
     * @return an instance of a read/write lock with a specified name
     */
    //public static DistributedReadWriteLock readWrite(String sName)
    //    {
    //    return f_mapReadWrite.computeIfAbsent(sName, n -> new DistributedReadWriteLock(n, readWriteLocksMap()));
    //    }

    // ----- helper methods -------------------------------------------------

    /**
     * Return Coherence {@link Session} for the Locks module.
     *
     * @return Coherence {@link Session} for the Locks module
     */
    protected static Session session()
        {
        return Coherence.findSession(SESSION_NAME)
                        .orElseThrow(() ->
                                new IllegalStateException(String.format("The session '%s' has not been initialized", SESSION_NAME)));
        }

    /**
     * Return Coherence {@link NamedMap} containing the exclusive locks state.
     *
     * @return Coherence {@link NamedMap} containing the exclusive locks state
     */
    protected static NamedMap<String, ExclusiveLockHolder> exclusiveLocksMap()
        {
        return session().getMap("locks-exclusive");
        }

    /**
     * Return Coherence {@link NamedMap} containing the read/write locks state.
     *
     * @return Coherence {@link NamedMap} containing the read/write locks state
     */
    protected static NamedMap<String, ExclusiveLockHolder> readWriteLocksMap()
        {
        return session().getMap("locks-read-write");
        }

    // ----- constants ------------------------------------------------------

    /**
     * The session name.
     */
    public static final String SESSION_NAME = ConcurrentServicesSessionConfiguration.SESSION_NAME;

    /**
     * A process-wide map of exclusive locks, to avoid creating multiple lock
     * instances (and thus sync objects) for the same server-side lock.
     */
    private static final Map<String, DistributedLock> f_mapExclusive = new ConcurrentHashMap<>();

    /*
     * A process-wide cache of read/write locks, to avoid creating multiple lock
     * instances (and thus sync objects) for the same server-side lock.
     */
    //private static final Map<String, DistributedReadWriteLock> f_mapReadWrite = new ConcurrentHashMap<>();
    }