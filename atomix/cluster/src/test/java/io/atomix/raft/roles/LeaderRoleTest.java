/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
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
package io.atomix.raft.roles;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.storage.log.RaftLogWriter;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.raft.storage.snapshot.SnapshotStore;
import io.atomix.raft.zeebe.ZeebeEntry;
import io.atomix.raft.zeebe.ZeebeLogAppender.AppendListener;
import io.atomix.storage.StorageException;
import io.atomix.storage.journal.Indexed;
import io.atomix.utils.concurrent.SingleThreadContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class LeaderRoleTest {

  private LeaderRole leaderRole;
  private RaftLogWriter writer;
  private RaftContext context;
  private RaftLogReader reader;

  @Before
  public void setup() {
    context = Mockito.mock(RaftContext.class);

    when(context.getName()).thenReturn("leader");
    when(context.getElectionTimeout()).thenReturn(Duration.ofMillis(100));
    when(context.getHeartbeatInterval()).thenReturn(Duration.ofMillis(100));

    final SingleThreadContext threadContext = new SingleThreadContext("leader");
    when(context.getThreadContext()).thenReturn(threadContext);

    writer = mock(RaftLogWriter.class);
    when(writer.getNextIndex()).thenReturn(1L);
    when(writer.append(any(ZeebeEntry.class)))
        .then(
            i -> {
              final ZeebeEntry zeebeEntry = i.getArgument(0);
              return new Indexed<>(1, zeebeEntry, 45);
            });
    when(context.getLogWriter()).thenReturn(writer);

    final SnapshotStore snapshotStore = mock(SnapshotStore.class);
    when(context.getSnapshotStore()).thenReturn(snapshotStore);

    leaderRole = new LeaderRole(context);
    // since we mock RaftContext we should simulate leader close on transition
    doAnswer(i -> leaderRole.stop().join()).when(context).transition(Role.FOLLOWER);
    when(context.getMembershipService()).thenReturn(mock(ClusterMembershipService.class));

    reader = mock(RaftLogReader.class);
    when(context.getLogReader()).thenReturn(reader);
  }

  @Test
  public void shouldAppendEntry() throws InterruptedException {
    // given
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {
            latch.countDown();
          }

          @Override
          public void onWriteError(final Throwable error) {}

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {}

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    assertEquals(0, latch.getCount());
  }

  @Test
  public void shouldRetryAppendEntryOnIOException() throws InterruptedException {
    // given

    when(writer.append(any(ZeebeEntry.class), anyLong()))
        .thenThrow(new StorageException(new IOException()))
        .thenThrow(new StorageException(new IOException()))
        .then(
            i -> {
              final ZeebeEntry zeebeEntry = i.getArgument(0);
              return new Indexed<>(1, zeebeEntry, 45);
            });

    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {
            latch.countDown();
          }

          @Override
          public void onWriteError(final Throwable error) {}

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {}

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(writer, timeout(1000).atLeast(3)).append(any(RaftLogEntry.class), anyLong());
  }

  @Test
  public void shouldStopRetryAppendEntryAfterMaxRetries() throws InterruptedException {
    // given
    when(writer.append(any(ZeebeEntry.class), anyLong()))
        .thenThrow(new StorageException(new IOException()));

    final AtomicReference<Throwable> catchedError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onWriteError(final Throwable error) {
            catchedError.set(error);
            latch.countDown();
          }

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {}

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(writer, timeout(1000).atLeast(5)).append(any(RaftLogEntry.class), anyLong());
    verify(context, timeout(1000)).transition(Role.FOLLOWER);
    assertTrue(catchedError.get() instanceof IOException);
  }

  @Test
  public void shouldStopAppendEntryOnOutOfDisk() throws InterruptedException {
    // given
    when(writer.append(any(ZeebeEntry.class), anyLong()))
        .thenThrow(new StorageException.OutOfDiskSpace("Boom file out"));

    final AtomicReference<Throwable> catchedError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onWriteError(final Throwable error) {
            catchedError.set(error);
            latch.countDown();
          }

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {}

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(context, timeout(1000)).transition(Role.FOLLOWER);
    verify(writer, timeout(1000)).append(any(RaftLogEntry.class), anyLong());

    assertTrue(catchedError.get() instanceof StorageException.OutOfDiskSpace);
  }

  @Test
  public void shouldStopAppendEntryOnToLargeEntry() throws InterruptedException {
    // given
    when(writer.append(any(ZeebeEntry.class), anyLong()))
        .thenThrow(new StorageException.TooLarge("Too large entry"));

    final AtomicReference<Throwable> catchedError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onWriteError(final Throwable error) {
            catchedError.set(error);
            latch.countDown();
          }

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {}

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(writer, timeout(1000)).append(any(RaftLogEntry.class), anyLong());

    assertTrue(catchedError.get() instanceof StorageException.TooLarge);
  }

  @Test
  public void shouldTransitionToFollowerWhenAppendEntryException() throws InterruptedException {
    // given
    when(writer.append(any(ZeebeEntry.class), anyLong()))
        .thenThrow(new RuntimeException("expected"));

    final AtomicReference<Throwable> caughtError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onWriteError(final Throwable error) {
            caughtError.set(error);
            latch.countDown();
          }

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {}

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(writer, timeout(1000)).append(any(RaftLogEntry.class), anyLong());
    verify(context, timeout(1000)).transition(Role.FOLLOWER);

    assertTrue(caughtError.get() instanceof RuntimeException);
  }

  @Test
  public void shouldNotAppendFollowingEntryOnException() throws InterruptedException {
    // given
    when(writer.append(any(ZeebeEntry.class), anyLong()))
        .thenThrow(new RuntimeException("expected"));

    final AtomicReference<Throwable> caughtError = new AtomicReference<>();
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onWriteError(final Throwable error) {
            caughtError.set(error);
            latch.countDown();
          }

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {}

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, mock(AppendListener.class));
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(context, timeout(1000)).transition(Role.FOLLOWER);
    verify(writer, timeout(1000)).append(any(RaftLogEntry.class), anyLong());

    assertTrue(caughtError.get() instanceof IllegalStateException);
    assertEquals(
        "LeaderRole is closed and cannot be used as appender", caughtError.get().getMessage());
  }

  @Test
  public void shouldRetryAppendEntriesInOrder() throws InterruptedException {
    // given
    when(writer.getNextIndex()).thenReturn(1L).thenReturn(2L);
    when(writer.append(any(ZeebeEntry.class), anyLong()))
        .thenThrow(new StorageException(new IOException("expected")))
        .thenThrow(new StorageException(new IOException("expected")))
        .then(
            i -> {
              final ZeebeEntry zeebeEntry = i.getArgument(0);
              return new Indexed<>(1, zeebeEntry, 45);
            });

    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final List<ZeebeEntry> entries = new CopyOnWriteArrayList<>();
    final CountDownLatch latch = new CountDownLatch(2);
    final AppendListener listener =
        new AppendListener() {

          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {
            entries.add(indexed.entry());
            latch.countDown();
          }

          @Override
          public void onWriteError(final Throwable error) {}

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {
            entry.setLowestPosition(index << 8);
            entry.setHighestPosition(index << 8);
          }

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);
    leaderRole.appendEntry(data, listener);

    // then
    latch.await(10, TimeUnit.SECONDS);
    verify(writer, timeout(1000).atLeast(3)).append(any(RaftLogEntry.class), anyLong());

    assertEquals(2, entries.size());
    assertEquals(1 << 8, entries.get(0).highestPosition());
    assertEquals(2 << 8, entries.get(1).highestPosition());
  }

  @Test
  public void shouldNotAppendInconsistentEntry() throws InterruptedException {
    // given
    final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, 1);
    final CountDownLatch latch = new CountDownLatch(1);
    final AppendListener listener =
        new AppendListener() {
          @Override
          public void onWrite(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onWriteError(final Throwable error) {
            latch.countDown();
          }

          @Override
          public void updateRecords(final ZeebeEntry entry, final long index) {
            throw new IllegalStateException("expected");
          }

          @Override
          public void onCommit(final Indexed<ZeebeEntry> indexed) {}

          @Override
          public void onCommitError(final Indexed<ZeebeEntry> indexed, final Throwable error) {}
        };

    // when
    leaderRole.appendEntry(data, listener);

    // then
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    verify(leaderRole.raft, timeout(2000).atLeast(1)).transition(Role.FOLLOWER);
  }
}
