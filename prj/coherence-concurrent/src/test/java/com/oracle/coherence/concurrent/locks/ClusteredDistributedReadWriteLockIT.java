/*
 * Copyright (c) 2000, 2021, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.concurrent.locks;

import com.oracle.bedrock.junit.CoherenceClusterExtension;

import com.oracle.bedrock.runtime.LocalPlatform;

import com.oracle.bedrock.runtime.coherence.CoherenceClusterMember;
import com.oracle.bedrock.runtime.coherence.options.ClusterPort;
import com.oracle.bedrock.runtime.coherence.options.LocalHost;
import com.oracle.bedrock.runtime.coherence.options.LocalStorage;
import com.oracle.bedrock.runtime.coherence.options.Logging;
import com.oracle.bedrock.runtime.coherence.options.Multicast;
import com.oracle.bedrock.runtime.coherence.options.RoleName;

import com.oracle.bedrock.runtime.concurrent.RemoteCallable;
import com.oracle.bedrock.runtime.concurrent.RemoteChannel;
import com.oracle.bedrock.runtime.concurrent.RemoteEvent;
import com.oracle.bedrock.runtime.concurrent.RemoteEventListener;

import com.oracle.bedrock.runtime.concurrent.callable.RemoteCallableStaticMethod;
import com.oracle.bedrock.runtime.java.options.ClassName;
import com.oracle.bedrock.runtime.java.options.IPv4Preferred;
import com.oracle.bedrock.runtime.options.DisplayName;

import com.oracle.bedrock.testsupport.junit.AbstractTestLogs;

import com.oracle.coherence.common.base.Logger;

import com.tangosol.net.Coherence;

import java.io.Serializable;

import java.time.Duration;
import java.time.Instant;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test distributed read/write locks across multiple cluster members.
 * <p>
 * This class must be Serializable so that its methods can be used as
 * remote callables by Bedrock.
 */
public class ClusteredDistributedReadWriteLockIT
        implements Serializable
    {
    @BeforeEach
    void beforeEach(TestInfo info)
        {
        // print a message in the logs of all the cluster members that are still running
        // to indicate the name of the test that is about to start
        String sMessage = ">>>>> Starting test method " + info.getDisplayName();
        logOnEachMember(sMessage);
        }

    @AfterEach
    void after(TestInfo info)
        {
        // print a message in the logs of all the cluster members that are still running
        // to indicate the name of the test that has just finished
        String sMessage = "<<<<< Completed test method " + info.getDisplayName();
        logOnEachMember(sMessage);
        }

    private void logOnEachMember(String sMessage)
        {
        coherenceResource.getCluster()
                .forEach(m ->
                         {
                         try
                             {
                             m.invoke(() ->
                                      {
                                      Logger.info(sMessage);
                                      return null;
                                      });
                             }
                         catch (Throwable ignore)
                             {
                             // ignoring "RemoteChannel is closed" exception
                             // from members that were shut down
                             }
                         });
        }

    @Test
    public void shouldAcquireAndReleaseLocksOnStorageMember()
        {
        // Get a storage member from the cluster
        CoherenceClusterMember member = coherenceResource.getCluster().get("storage-1");

        // If any assertions in the methods below fail this method will throw an exception
        member.invoke(this::shouldAcquireAndReleaseWriteLock);
        member.invoke(this::shouldAcquireAndReleaseReadLock);
        }

    @Test
    public void shouldAcquireAndReleaseLocksOnStorageDisabledMember()
        {
        // Get a storage disabled application member from the cluster
        CoherenceClusterMember member = coherenceResource.getCluster().get("application-1");

        // If any assertions in the methods below fail this method will throw an exception
        member.invoke(this::shouldAcquireAndReleaseWriteLock);
        member.invoke(this::shouldAcquireAndReleaseReadLock);
        }

    /**
     * This test method is invoked on remote processes by Bedrock.
     *
     * This method must have a return value as it is invoked as a
     * RemoteCallable so that the invoke call blocks until the
     * method has completes. In this case we do not care about the
     * actual return value, so we use Void.
     *
     * If any of the assertions fail, the invoke call in the test will fail.
     *
     * @return always returns Void (null).
     */
    Void shouldAcquireAndReleaseWriteLock()
        {
        Logger.info("In shouldAcquireAndReleaseWriteLock()");
        DistributedReadWriteLock lock = Locks.remoteReadWriteLock("foo");

        lock.writeLock().lock();
        System.out.println("Write lock acquired by " + lock.getOwner());
        assertThat(lock.isWriteLocked(), is(true));
        assertThat(lock.isWriteLockedByCurrentThread(), is(true));
        assertThat(lock.getWriteHoldCount(), is(1));

        lock.writeLock().unlock();
        assertThat(lock.isWriteLocked(), is(false));
        assertThat(lock.isWriteLockedByCurrentThread(), is(false));
        assertThat(lock.getWriteHoldCount(), is(0));
        System.out.println("Write lock released by " + Thread.currentThread());
        return null;
        }

    Void shouldAcquireAndReleaseReadLock()
        {
        Logger.info("In shouldAcquireAndReleaseReadLock()");
        DistributedReadWriteLock lock = Locks.remoteReadWriteLock("foo");

        lock.readLock().lock();
        System.out.println("Read lock acquired by " + Thread.currentThread());
        assertThat(lock.isReadLocked(), is(true));
        assertThat(lock.getReadLockCount(), is(1));
        assertThat(lock.getReadHoldCount(), is(1));

        lock.readLock().unlock();
        assertThat(lock.isReadLocked(), is(false));
        assertThat(lock.getReadLockCount(), is(0));
        assertThat(lock.getReadHoldCount(), is(0));
        System.out.println("Read lock released by " + Thread.currentThread());
        return null;
        }

    @Test
    void shouldTimeOutIfWriteLockIsHeldByAnotherMemberUsingStorageMembers() throws Exception
        {
        // Get storage members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("storage-1");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("storage-2");

        shouldTimeOutIfWriteLockIsHeldByAnotherMember(member1, member2);
        }

    @Test
    void shouldTimeOutIfWriteLockIsHeldByAnotherMemberUsingStorageDisabledMembers() throws Exception
        {
        // Get storage disabled application members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("application-1");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("application-2");

        shouldTimeOutIfWriteLockIsHeldByAnotherMember(member1, member2);
        }

    /**
     * This test acquires a write lock on one cluster member for a specific duration and then tries to acquire
     * the same lock on another member.
     *
     * @param member1  the member to acquire the lock on
     * @param member2  the member to try to acquire the lock on
     *
     * @throws Exception if the test fails
     */
    void shouldTimeOutIfWriteLockIsHeldByAnotherMember(CoherenceClusterMember member1, CoherenceClusterMember member2) throws Exception
        {
        String            sLockName = "foo";
        LockEventListener listener1  = new LockEventListener(sLockName);
        LockEventListener listener2  = new LockEventListener(sLockName);

        // Add the listeners to listen for lock events
        member1.addListener(listener1);
        member2.addListener(listener2);

        // Acquire the lock on first member (the lock will be held for 5 seconds)
        member1.submit(new AcquireWriteLock(sLockName, Duration.ofSeconds(5)));
        // wait for the lock acquired event
        listener1.awaitWriteAcquired(Duration.ofMinutes(1));

        // try to acquire read lock on the second member (should time out after 500 millis)
        TryReadLock tryReadLock = new TryReadLock(sLockName, Duration.ofMillis(500));
        CompletableFuture<Boolean> futureTryRead = member2.submit(tryReadLock);
        assertThat(futureTryRead.get(), is(false));

        // try to acquire write lock on the second member (should time out after 500 millis)
        TryWriteLock tryWriteLock = new TryWriteLock(sLockName, Duration.ofMillis(500));
        CompletableFuture<Boolean> futureTryWrite = member2.submit(tryWriteLock);
        assertThat(futureTryWrite.get(), is(false));

        // wait for the write lock released event from the first member
        listener1.awaitWriteReleased(Duration.ofMinutes(1));

        // try again to acquire the write lock on the second member (should succeed)
        futureTryWrite = member2.submit(tryWriteLock);
        assertThat(futureTryWrite.get(), is(true));

        // wait for the write lock acquired and released event from the second member
        listener2.awaitWriteAcquired(Duration.ofMinutes(1));
        listener2.awaitWriteReleased(Duration.ofMinutes(1));

        // try again to acquire the read lock on the second member (should succeed)
        futureTryRead = member2.submit(tryReadLock);
        assertThat(futureTryRead.get(), is(true));

        // wait for the read lock acquired and released event from the second member
        listener2.awaitReadAcquired(Duration.ofMinutes(1));
        listener2.awaitReadReleased(Duration.ofMinutes(1));
        }

    @Test
    void shouldAcquireReadLockFromMultipleStorageMembers() throws Exception
        {
        // Get storage members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("storage-1");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("storage-2");

        shouldAcquireReadLockFromMultipleMembers(member1, member2);
        }

    @Test
    void shouldAcquireReadLockFromMultipleStorageDisabledMembers() throws Exception
        {
        // Get storage disabled application members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("application-1");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("application-2");

        shouldAcquireReadLockFromMultipleMembers(member1, member2);
        }

    @Test
    void shouldAcquireReadLockFromAllMembers() throws Exception
        {
        // Get all members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("storage-1");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("storage-2");
        CoherenceClusterMember member3 = coherenceResource.getCluster().get("application-1");
        CoherenceClusterMember member4 = coherenceResource.getCluster().get("application-2");

        shouldAcquireReadLockFromMultipleMembers(member1, member2, member3, member4);
        }

    /**
     * This test acquires a read lock on each specified cluster member for a specific duration.
     *
     * @param aMembers  the members to acquire the read lock on
     *
     * @throws Exception if the test fails
     */
    void shouldAcquireReadLockFromMultipleMembers(CoherenceClusterMember... aMembers) throws Exception
        {
        String sLockName = "foo";

        Set<CoherenceClusterMember> members = Set.of(aMembers);

        // Add the listeners to listen for lock events from each first member.
        Set<LockEventListener> listeners = new HashSet<>();
        for (CoherenceClusterMember member : members)
            {
            LockEventListener listener = new LockEventListener(sLockName);
            member.addListener(listener);
            listeners.add(listener);
            }

        // Acquire the read lock on each member (the lock will be held for 5 seconds)
        members.forEach(member -> member.submit(new AcquireReadLock(sLockName, Duration.ofSeconds(5))));

        // wait for the lock acquired event from each member
        for (LockEventListener listener : listeners)
            {
            listener.awaitReadAcquired(Duration.ofMinutes(1));
            }

        // wait for the lock released event from each member
        for (LockEventListener listener : listeners)
            {
            listener.awaitReadReleased(Duration.ofMinutes(1));
            }
        }

    @Test
    void shouldAcquireAndReleaseLockInOrderFromMultipleStorageMembers() throws Exception
        {
        // Get storage members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("storage-1");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("storage-2");

        shouldAcquireAndReleaseLockInOrderFromMultipleMembers(member1, member2);
        }

    @Test
    void shouldAcquireAndReleaseLockInOrderFromMultipleStorageDisabledMembers() throws Exception
        {
        // Get storage members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("application-1");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("application-2");

        shouldAcquireAndReleaseLockInOrderFromMultipleMembers(member1, member2);
        }

    /**
     * This test acquires the same lock from multiple members.
     * The first member should acquire the lock and the second member should block until the
     * first has released the lock.
     *
     * @param member1  the first member to acquire the lock
     * @param member2  the second member to acquire the lock
     *
     * @throws Exception if the test fails
     */
    void shouldAcquireAndReleaseLockInOrderFromMultipleMembers(CoherenceClusterMember member1, CoherenceClusterMember member2) throws Exception
        {
        String            sLockName = "foo";
        LockEventListener listener1 = new LockEventListener(sLockName);
        LockEventListener listener2 = new LockEventListener(sLockName);

        // Add the listeners to listen for lock events
        member1.addListener(listener1);
        member2.addListener(listener2);

        // Acquire the lock on first member (the lock will be held for 5 seconds)
        member1.submit(new AcquireWriteLock(sLockName, Duration.ofSeconds(5)));
        // wait for the lock acquired event
        listener1.awaitWriteAcquired(Duration.ofMinutes(1));

        // Try to acquire the lock on second member (should fail)
        assertThat(member2.invoke(new TryWriteLock(sLockName)), is(false));

        // Acquire the lock on the second member, should block until the first member releases
        member2.submit(new AcquireWriteLock(sLockName, Duration.ofSeconds(5)));

        // wait for the second member to acquire the lock (should be after member 1 releases the lock)
        listener2.awaitWriteAcquired(Duration.ofMinutes(1));
        // wait for the second member to release the lock
        listener2.awaitWriteReleased(Duration.ofMinutes(1));

        // Assert the locks were acquired and released in the order expected
        assertThat(listener1.getWriteAcquiredAt().isBefore(listener1.getWriteReleasedAt()), is(true));
        assertThat(listener1.getWriteReleasedAt().isBefore(listener2.getWriteAcquiredAt()), is(true));
        assertThat(listener2.getWriteAcquiredAt().isBefore(listener2.getWriteReleasedAt()), is(true));
        }

    @Test
    void shouldAcquireLockHeldByFailedStorageMember() throws Exception
        {
        // Get storage members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("storage-3");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("storage-2");

        shouldAcquireLockHeldByFailedMember(member1, member2);
        }

    @Test
    void shouldAcquireLockHeldByFailedStorageDisabledMember() throws Exception
        {
        // Get storage disabled application members from the cluster
        CoherenceClusterMember member1 = coherenceResource.getCluster().get("application-3");
        CoherenceClusterMember member2 = coherenceResource.getCluster().get("application-2");

        shouldAcquireLockHeldByFailedMember(member1, member2);
        }

    /**
     * This test checks that a lock held by a failed member is automatically released,
     * and subsequently acquired by another member.
     *
     * @param member1  the first member to acquire the lock on and then kill
     * @param member2  the second member to try to acquire the lock on
     *
     * @throws Exception if the test fails
     */
    void shouldAcquireLockHeldByFailedMember(CoherenceClusterMember member1, CoherenceClusterMember member2) throws Exception
        {
        LockEventListener foo1  = new LockEventListener("foo");
        LockEventListener foo2  = new LockEventListener("foo");
        LockEventListener bar1  = new LockEventListener("bar");
        LockEventListener bar2  = new LockEventListener("bar");

        // Add the listeners to listen for lock events from the first member.
        member1.addListener(foo1);
        member1.addListener(bar1);
        // Add the listeners to listen for lock events from the second member.
        member2.addListener(foo2);
        member2.addListener(bar2);

        // Acquire read and write lock on first member (the lock will be held for 1 minute,
        // but should be released as soon as the member is killed)
        member1.submit(new AcquireWriteLock("foo", Duration.ofMinutes(1)));
        member1.submit(new AcquireReadLock("bar", Duration.ofMinutes(1)));

        // wait for write and read lock acquired event
        foo1.awaitWriteAcquired(Duration.ofMinutes(1));
        bar1.awaitReadAcquired(Duration.ofMinutes(1));

        // Acquire write locks on second member
        member2.submit(new AcquireWriteLock("foo", Duration.ofSeconds(5)));
        member2.submit(new AcquireWriteLock("bar", Duration.ofSeconds(5)));

        // Kill first member
        RemoteCallable<Void> exit = new RemoteCallableStaticMethod<>("java.lang.System", "exit", 1);
        member1.submit(exit);

        // wait for the lock acquired and released events from the second member
        foo2.awaitWriteAcquired(Duration.ofMinutes(1));
        bar2.awaitWriteAcquired(Duration.ofMinutes(1));
        foo2.awaitWriteReleased(Duration.ofMinutes(1));
        bar2.awaitWriteReleased(Duration.ofMinutes(1));
        }

    // ----- inner class: TryWriteLock --------------------------------------

    /**
     * A Bedrock remote callable that tries to acquire a lock within a given timeout.
     * <p>
     * The result of the call to {@link DistributedReadWriteLock.WriteLock#tryLock()} is returned.
     * If the lock was acquired it is immediately released.
     */
    static class TryWriteLock
            implements RemoteCallable<Boolean>
        {
        /**
         * A remote channel injected by Bedrock and used to fire events back to the test.
         */
        @RemoteChannel.Inject
        private RemoteChannel remoteChannel;

        /**
         * The name of the lock to acquire.
         */
        private final String f_sLockName;

        /**
         * The amount of time to wait to acquire the lock.
         */
        private final Duration f_timeout;

        /**
         * Create a {@link TryWriteLock} callable.
         *
         * @param sLockName  the name of the lock to acquire
         */
        public TryWriteLock(String sLockName)
            {
            f_sLockName = sLockName;
            f_timeout   = Duration.ZERO;
            }

        /**
         * Create a {@link TryWriteLock} callable.
         *
         * @param sLockName  the name of the lock to acquire
         * @param duration   the amount of time to wait to acquire the lock
         */
        public TryWriteLock(String sLockName, Duration duration)
            {
            f_sLockName = sLockName;
            f_timeout = duration;
            }

        @Override
        public Boolean call() throws Exception
            {
            DistributedReadWriteLock lock = Locks.remoteReadWriteLock(f_sLockName);

            boolean fAcquired;
            if (f_timeout.isZero())
                {
                Logger.info("Trying to acquire write lock " + f_sLockName + " with zero timeout");
                fAcquired = lock.writeLock().tryLock();
                }
            else
                {
                Logger.info("Trying to acquire write lock " + f_sLockName + " with timeout of " + f_timeout);
                fAcquired = lock.writeLock().tryLock(f_timeout.toMillis(), TimeUnit.MILLISECONDS);
                }

            if (fAcquired)
                {
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.WriteAcquired));
                Logger.info("Tried and succeeded to acquire write lock " + f_sLockName + " within timeout " + f_timeout);
                lock.writeLock().unlock();
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.WriteReleased));
                }
            else
                {
                Logger.info("Tried and failed to acquire write lock " + f_sLockName + " within timeout " + f_timeout);
                }

            return fAcquired;
            }
        }

    // ----- inner class: TryWriteLock --------------------------------------

    /**
     * A Bedrock remote callable that tries to acquire a read lock within a given timeout.
     * <p>
     * The result of the call to {@link DistributedReadWriteLock.ReadLock#tryLock()} is returned.
     * If the lock was acquired it is immediately released.
     */
    static class TryReadLock
            implements RemoteCallable<Boolean>
        {
        /**
         * A remote channel injected by Bedrock and used to fire events back to the test.
         */
        @RemoteChannel.Inject
        private RemoteChannel remoteChannel;

        /**
         * The name of the lock to acquire.
         */
        private final String f_sLockName;

        /**
         * The amount of time to wait to acquire the lock.
         */
        private final Duration f_timeout;

        /**
         * Create a {@link TryReadLock} callable.
         *
         * @param sLockName  the name of the lock to acquire
         */
        public TryReadLock(String sLockName)
            {
            f_sLockName = sLockName;
            f_timeout   = Duration.ZERO;
            }

        /**
         * Create a {@link TryReadLock} callable.
         *
         * @param sLockName  the name of the lock to acquire
         * @param duration   the amount of time to wait to acquire the lock
         */
        public TryReadLock(String sLockName, Duration duration)
            {
            f_sLockName = sLockName;
            f_timeout = duration;
            }

        @Override
        public Boolean call() throws Exception
            {
            DistributedReadWriteLock lock = Locks.remoteReadWriteLock(f_sLockName);

            boolean fAcquired;
            if (f_timeout.isZero())
                {
                Logger.info("Trying to acquire read lock " + f_sLockName + " with zero timeout");
                fAcquired = lock.readLock().tryLock();
                }
            else
                {
                Logger.info("Trying to acquire read lock " + f_sLockName + " with timeout of " + f_timeout);
                fAcquired = lock.readLock().tryLock(f_timeout.toMillis(), TimeUnit.MILLISECONDS);
                }

            if (fAcquired)
                {
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.ReadAcquired));
                Logger.info("Tried and succeeded to acquire read lock " + f_sLockName + " within timeout " + f_timeout);
                lock.readLock().unlock();
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.ReadReleased));
                }
            else
                {
                Logger.info("Tried and failed to acquire read lock " + f_sLockName + " within timeout " + f_timeout);
                }

            return fAcquired;
            }
        }

    // ----- inner class: AcquireWriteLock ----------------------------------

    /**
     * A Bedrock remote callable that acquires a lock for a specific amount of time.
     * <p>
     * This callable fires remote events to indicate when the lock was acquired and released.
     */
    static class AcquireWriteLock
            implements RemoteCallable<Void>
        {
        /**
         * A remote channel injected by Bedrock and used to fire events back to the test.
         */
        @RemoteChannel.Inject
        private RemoteChannel remoteChannel;

        /**
         * The name of the lock to acquire.
         */
        private final String f_sLockName;

        /**
         * The duration to hold the lock for.
         */
        private final Duration f_duration;

        /**
         * Create an {@link AcquireWriteLock} callable.
         *
         * @param sLockName  the name of the lock to acquire
         * @param duration   the duration to hold the lock for
         */
        AcquireWriteLock(String sLockName, Duration duration)
            {
            f_sLockName = sLockName;
            f_duration  = duration;
            }

        @Override
        public Void call()
            {
            Logger.info("Acquiring write lock " + f_sLockName);
            DistributedReadWriteLock lock = Locks.remoteReadWriteLock(f_sLockName);
            lock.writeLock().lock();
            try
                {
                Logger.info("Write lock " + f_sLockName + " acquired by " + lock.getOwner());
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.WriteAcquired));
                Thread.sleep(f_duration.toMillis());
                }
            catch (InterruptedException ignore)
                {
                }
            finally
                {
                lock.writeLock().unlock();
                Logger.info("Write lock " + f_sLockName + " released by " + Thread.currentThread());
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.WriteReleased));
                }
            return null;
            }
        }

    // ----- inner class: AcquireReadLock ----------------------------------

    /**
     * A Bedrock remote callable that acquires a lock for a specific amount of time.
     * <p>
     * This callable fires remote events to indicate when the lock was acquired and released.
     */
    static class AcquireReadLock
            implements RemoteCallable<Void>
        {
        /**
         * A remote channel injected by Bedrock and used to fire events back to the test.
         */
        @RemoteChannel.Inject
        private RemoteChannel remoteChannel;

        /**
         * The name of the lock to acquire.
         */
        private final String f_sLockName;

        /**
         * The duration to hold the lock for.
         */
        private final Duration f_duration;

        /**
         * Create an {@link AcquireWriteLock} callable.
         *
         * @param sLockName  the name of the lock to acquire
         * @param duration   the duration to hold the lock for
         */
        AcquireReadLock(String sLockName, Duration duration)
            {
            f_sLockName = sLockName;
            f_duration  = duration;
            }

        @Override
        public Void call()
            {
            Logger.info("Acquiring read lock " + f_sLockName);
            DistributedReadWriteLock lock = Locks.remoteReadWriteLock(f_sLockName);
            lock.readLock().lock();
            try
                {
                Logger.info("Read lock " + f_sLockName + " acquired by " + Thread.currentThread());
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.ReadAcquired));
                Thread.sleep(f_duration.toMillis());
                }
            catch (InterruptedException ignore)
                {
                }
            finally
                {
                lock.readLock().unlock();
                Logger.info("Read lock " + f_sLockName + " released by " + Thread.currentThread());
                remoteChannel.raise(new LockEvent(f_sLockName, LockEventType.ReadReleased));
                }
            return null;
            }
        }

    // ----- inner class: LockEvent -----------------------------------------

    /**
     * A Bedrock remote event submitted by the {@link AcquireWriteLock} callable
     * to notify the calling test when the lock has been acquired and released.
     */
    static class LockEvent
            implements RemoteEvent
        {
        /**
         * The name of the lock.
         */
        private final String f_sLockName;

        /**
         * The type of the event.
         */
        private final LockEventType f_type;

        /**
         * Create a lock event.
         *
         * @param sLockName  the name of the lock
         * @param type       the type of the event
         */
        public LockEvent(String sLockName, LockEventType type)
            {
            f_sLockName = sLockName;
            f_type      = type;
            }

        /**
         * Returns the name of the lock.
         *
         * @return  the name of the lock
         */
        public String getLockName()
            {
            return f_sLockName;
            }

        /**
         * Returns the event type.
         *
         * @return  the event type
         */
        public LockEventType getEventType()
            {
            return f_type;
            }
        }

    // ----- inner class LockEventListener ----------------------------------

    /**
     * A {@link RemoteEventListener} that listens for {@link LockEvent lock events}.
     */
    static class LockEventListener
            implements RemoteEventListener
        {
        /**
         * The name of the lock.
         */
        private final String f_sLockName;

        /**
         * A future that completes when the read lock acquired event is received.
         */
        private final CompletableFuture<Void> f_futureReadAcquired = new CompletableFuture<>();

        /**
         * A future that completes when the read lock released event is received.
         */
        private final CompletableFuture<Void> f_futureReadReleased = new CompletableFuture<>();

        /**
         * A future that completes when the write lock acquired event is received.
         */
        private final CompletableFuture<Void> f_futureWriteAcquired = new CompletableFuture<>();

        /**
         * A future that completes when the write lock released event is received.
         */
        private final CompletableFuture<Void> f_futureWriteReleased = new CompletableFuture<>();

        /**
         * The time the read lock was acquired.
         */
        private Instant m_readAcquiredAt;

        /**
         * The time the read lock was released.
         */
        private Instant m_readReleasedAt;

        /**
         * The time the write lock was acquired.
         */
        private Instant m_writeAcquiredAt;

        /**
         * The time the write lock was released.
         */
        private Instant m_writeReleasedAt;

        /**
         * Create a {@link LockEventListener}.
         *
         * @param sLockName  the name of the lock
         */
        public LockEventListener(String sLockName)
            {
            f_sLockName = sLockName;
            }

        @Override
        public void onEvent(RemoteEvent event)
            {
            if (event instanceof LockEvent && f_sLockName.equals(((LockEvent) event).getLockName()))
                {
                switch (((LockEvent) event).getEventType())
                    {
                    case ReadAcquired:
                        m_readAcquiredAt = Instant.now();
                        f_futureReadAcquired.complete(null);
                        break;
                    case ReadReleased:
                        m_readReleasedAt = Instant.now();
                        f_futureReadReleased.complete(null);
                        break;
                    case WriteAcquired:
                        m_writeAcquiredAt = Instant.now();
                        f_futureWriteAcquired.complete(null);
                        break;
                    case WriteReleased:
                        m_writeReleasedAt = Instant.now();
                        f_futureWriteReleased.complete(null);
                        break;
                    }
                }
            }

        /**
         * Wait for the read lock acquired event.
         *
         * @param timeout  the maximum amount of time to wait
         */
        public void awaitReadAcquired(Duration timeout) throws Exception
            {
            f_futureReadAcquired.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

        /**
         * Wait for the write lock acquired event.
         *
         * @param timeout  the maximum amount of time to wait
         */
        public void awaitWriteAcquired(Duration timeout) throws Exception
            {
            f_futureWriteAcquired.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

        /**
         * Returns true if the read lock has been acquired.
         *
         * @return true if the read lock has been acquired
         */
        public boolean isReadAcquired()
            {
            return f_futureReadAcquired.isDone();
            }

        /**
         * Returns true if the write lock has been acquired.
         *
         * @return true if the write lock has been acquired
         */
        public boolean isWriteAcquired()
            {
            return f_futureWriteAcquired.isDone();
            }

        /**
         * Returns the time that the read lock was acquired.
         *
         * @return the time that the read lock was acquired
         */
        public Instant getReadAcquiredAt()
            {
            return m_readAcquiredAt;
            }

        /**
         * Returns the time that the write lock was acquired.
         *
         * @return the time that the write lock was acquired
         */
        public Instant getWriteAcquiredAt()
            {
            return m_writeAcquiredAt;
            }

        /**
         * Wait for the read lock released event.
         *
         * @param timeout  the maximum amount of time to wait
         */
        public void awaitReadReleased(Duration timeout) throws Exception
            {
            f_futureReadReleased.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

        /**
         * Wait for the write lock released event.
         *
         * @param timeout  the maximum amount of time to wait
         */
        public void awaitWriteReleased(Duration timeout) throws Exception
            {
            f_futureWriteReleased.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

        /**
         * Returns true if the read lock has been acquired and released.
         *
         * @return true if the read lock has been acquired and released
         */
        public boolean isReadReleased()
            {
            return f_futureReadAcquired.isDone() && f_futureReadReleased.isDone();
            }

        /**
         * Returns true if the write lock has been acquired and released.
         *
         * @return true if the write lock has been acquired and released
         */
        public boolean isWriteReleased()
            {
            return f_futureWriteAcquired.isDone() && f_futureWriteReleased.isDone();
            }

        /**
         * Returns the time that the write lock was released.
         *
         * @return the time that the write lock was released
         */
        public Instant getReadReleasedAt()
            {
            return m_readReleasedAt;
            }

        /**
         * Returns the time that the write lock was released.
         *
         * @return the time that the write lock was released
         */
        public Instant getWriteReleasedAt()
            {
            return m_writeReleasedAt;
            }
        }

    // ----- inner enum LockEventType ---------------------------------------

    /**
     * An enum of lock event types.
     */
    enum LockEventType
        {
        ReadAcquired,
        ReadReleased,
        WriteAcquired,
        WriteReleased
        }

    // ----- data members ---------------------------------------------------

    /**
     * A Bedrock utility to capture logs of spawned processes into files
     * under target/test-output. This is added as an option to the cluster
     * and client processes.
     */
    static TestLogs logs = new TestLogs(ClusteredDistributedReadWriteLockIT.class);

    /**
     * A Bedrock JUnit5 extension that starts a Coherence cluster made up of
     * two storage enabled members, two storage disabled members and two
     * storage disabled extend proxy members.
     */
    @RegisterExtension
    static CoherenceClusterExtension coherenceResource =
            new CoherenceClusterExtension()
                    .using(LocalPlatform.get())
                    .with(ClassName.of(Coherence.class),
                          Logging.at(9),
                          LocalHost.only(),
                          Multicast.ttl(0),
                          IPv4Preferred.yes(),
                          logs,
                          ClusterPort.automatic())
                    .include(3,
                             DisplayName.of("storage"),
                             RoleName.of("storage"),
                             LocalStorage.enabled())
                    .include(3,
                             DisplayName.of("application"),
                             RoleName.of("application"),
                             LocalStorage.disabled());

    /**
     * This is a work-around to fix the fact that the JUnit5 test logs extension
     * in Bedrock does not work for BeforeAll methods and extensions.
     */
    static class TestLogs
            extends AbstractTestLogs
        {
        public TestLogs(Class<?> testClass)
            {
            init(testClass, "BeforeAll");
            }
        }
    }