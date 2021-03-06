/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gobblin.writer;

import java.io.IOException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rholder.retry.RetryerBuilder;
import com.google.common.base.Preconditions;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.configuration.State;
import org.apache.gobblin.records.ControlMessageHandler;
import org.apache.gobblin.records.FlushControlMessageHandler;
import org.apache.gobblin.stream.RecordEnvelope;
import org.apache.gobblin.util.Decorator;
import org.apache.gobblin.util.FinalState;

/**
 * The {@link CloseOnFlushWriterWrapper} closes the wrapped writer on flush and creates a new writer using a
 * {@link Supplier} on the next write. After the writer is closed the reference is still available for inspection until
 * a new writer is created on the next write.
 * @param <D>
 */
public class CloseOnFlushWriterWrapper<D> extends WriterWrapper<D> implements Decorator, FinalState, Retriable {
  private static final Logger LOG = LoggerFactory.getLogger(CloseOnFlushWriterWrapper.class);

  private final State state;
  private DataWriter<D> writer;
  private final Supplier<DataWriter<D>> writerSupplier;
  private boolean closed;
  // is the close functionality enabled?
  private final boolean closeOnFlush;

  public CloseOnFlushWriterWrapper(Supplier<DataWriter<D>> writerSupplier, State state) {
    Preconditions.checkNotNull(state, "State is required.");

    this.state = state;
    this.writerSupplier = writerSupplier;

    this.writer = writerSupplier.get();
    this.closed = false;

    this.closeOnFlush = this.state.getPropAsBoolean(ConfigurationKeys.WRITER_CLOSE_ON_FLUSH_KEY,
        ConfigurationKeys.DEFAULT_WRITER_CLOSE_ON_FLUSH);
  }

  @Override
  public Object getDecoratedObject() {
    return this.writer;
  }

  @Override
  public void writeEnvelope(RecordEnvelope<D> record) throws IOException {
    // get a new writer if last one was closed
    if (this.closed) {
      this.writer = writerSupplier.get();
      this.closed = false;
    }
    this.writer.writeEnvelope(record);
  }

  @Override
  public void close() throws IOException {
    writer.close();
    this.closed = true;
  }

  @Override
  public void commit() throws IOException {
    writer.commit();
  }

  @Override
  public void cleanup() throws IOException {
    writer.cleanup();

  }

  @Override
  public long recordsWritten() {
    return writer.recordsWritten();
  }

  @Override
  public long bytesWritten() throws IOException {
    return writer.bytesWritten();
  }

  @Override
  public RetryerBuilder<Void> getRetryerBuilder() {
    if (writer instanceof Retriable) {
      return ((Retriable) writer).getRetryerBuilder();
    }
    return RetryWriter.createRetryBuilder(state);
  }

  @Override
  public State getFinalState() {
    State state = new State();

    if (this.writer instanceof FinalState) {
      state.addAll(((FinalState)this.writer).getFinalState());
    } else {
      LOG.warn("Wrapped writer does not implement FinalState: " + this.writer.getClass());
    }

    return state;
  }

  @Override
  public ControlMessageHandler getMessageHandler() {
    // if close on flush is configured then create a handler that will invoke the wrapper's flush to perform close
    // on flush operations, otherwise return the wrapped writer's handler.
    if (this.closeOnFlush) {
      return new FlushControlMessageHandler(this);
    } else {
      return this.writer.getMessageHandler();
    }
  }

  /**
   * The writer will be flushed. It will also be committed and closed if configured to be closed on flush.
   * @throws IOException
   */
  @Override
  public void flush() throws IOException {
    this.writer.flush();

    // commit data then close the writer
    if (this.closeOnFlush) {
      commit();
      close();
    }
  }
}
