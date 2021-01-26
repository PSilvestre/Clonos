/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.causal.EpochTracker;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.serialization.RecordDeserializer;
import org.apache.flink.runtime.io.network.api.serialization.RecordDeserializer.DeserializationResult;
import org.apache.flink.runtime.io.network.api.serialization.SpillingAdaptiveSpanningRecordDeserializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.runtime.plugable.DeserializationDelegate;
import org.apache.flink.runtime.plugable.NonReusingDeserializationDelegate;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.streamrecord.StreamElement;
import org.apache.flink.streaming.runtime.streamrecord.StreamElementSerializer;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StatusWatermarkValve;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.tasks.StreamTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Input reader for {@link org.apache.flink.streaming.runtime.tasks.OneInputStreamTask}.
 *
 * <p>This internally uses a {@link StatusWatermarkValve} to keep track of {@link Watermark} and
 * {@link StreamStatus} events, and forwards them to event subscribers once the
 * {@link StatusWatermarkValve} determines the {@link Watermark} from all inputs has advanced, or
 * that a {@link StreamStatus} needs to be propagated downstream to denote a status change.
 *
 * <p>Forwarding elements, watermarks, or status status elements must be protected by synchronizing
 * on the given lock object. This ensures that we don't call methods on a
 * {@link OneInputStreamOperator} concurrently with the timer callback or other things.
 *
 * @param <IN> The type of the record that can be read with this record reader.
 */
@Internal
public class StreamInputProcessor<IN> {

	private static final Logger LOG = LoggerFactory.getLogger(StreamInputProcessor.class);

	private final RecordDeserializer<DeserializationDelegate<StreamElement>>[] recordDeserializers;

	private RecordDeserializer<DeserializationDelegate<StreamElement>> currentRecordDeserializer;

	private final DeserializationDelegate<StreamElement> deserializationDelegate;

	private final CheckpointBarrierHandler barrierHandler;

	private final Object lock;

	// ---------------- Status and Watermark Valve ------------------

	/**
	 * Valve that controls how watermarks and stream statuses are forwarded.
	 */
	private StatusWatermarkValve statusWatermarkValve;

	private final InputGate inputGate;

	/**
	 * The channel from which a buffer came, tracked so that we can appropriately map
	 * the watermarks and watermark statuses to channel indexes of the valve.
	 */
	private int currentChannel = -1;

	private final StreamStatusMaintainer streamStatusMaintainer;

	private final OneInputStreamOperator<IN, ?> streamOperator;

	// ---------------- Metrics ------------------

	private final WatermarkGauge watermarkGauge;
	private Counter numRecordsIn;

	private boolean isFinished;

	private final EpochTracker epochTracker;


	@SuppressWarnings("unchecked")
	public StreamInputProcessor(
		SingleInputGate[] inputGates,
		TypeSerializer<IN> inputSerializer,
		StreamTask<?, ?> checkpointedTask,
		CheckpointingMode checkpointMode,
		Object lock,
		IOManager ioManager,
		Configuration taskManagerConfig,
		StreamStatusMaintainer streamStatusMaintainer,
		OneInputStreamOperator<IN, ?> streamOperator,
		TaskIOMetricGroup metrics,
		WatermarkGauge watermarkGauge) throws IOException {


		this.epochTracker = checkpointedTask.getRecordCounter();
		inputGate = InputGateUtil.createInputGate(inputGates);
		checkpointedTask.getRecoveryManager().getContext().setInputGate(inputGate);

		this.barrierHandler = InputProcessorUtil.createCheckpointBarrierHandler(
			checkpointedTask, checkpointMode, ioManager, inputGate, taskManagerConfig);

		this.lock = checkNotNull(lock);

		StreamElementSerializer<IN> ser = new StreamElementSerializer<>(inputSerializer);
		this.deserializationDelegate = new NonReusingDeserializationDelegate<>(ser);

		// Initialize one deserializer per input channel
		this.recordDeserializers =
			new SpillingAdaptiveSpanningRecordDeserializer[inputGate.getNumberOfInputChannels()];

		for (int i = 0; i < recordDeserializers.length; i++) {
			recordDeserializers[i] = new SpillingAdaptiveSpanningRecordDeserializer<>(
				ioManager.getSpillingDirectoriesPaths());
		}

		/**
		 * Number of input channels the valve needs to handle.
		 */
		int numInputChannels = inputGate.getNumberOfInputChannels();

		this.streamStatusMaintainer = checkNotNull(streamStatusMaintainer);
		this.streamOperator = checkNotNull(streamOperator);

		this.statusWatermarkValve = new StatusWatermarkValve(
			numInputChannels,
			new ForwardingValveOutputHandler(streamOperator, lock));

		this.watermarkGauge = watermarkGauge;
		metrics.gauge("checkpointAlignmentTime", barrierHandler::getAlignmentDurationNanos);
	}

	public boolean processInput() throws Exception {
		if (isFinished)
			return false;

		if (numRecordsIn == null) {
			try {
				numRecordsIn =
					((OperatorMetricGroup) streamOperator.getMetricGroup()).getIOMetricGroup().getNumRecordsInCounter();
			} catch (Exception e) {
				LOG.warn("An exception occurred during the metrics setup.", e);
				numRecordsIn = new SimpleCounter();
			}
		}

		if (currentRecordDeserializer != null) {
			DeserializationResult result = currentRecordDeserializer.getNextRecord(deserializationDelegate);

			if (result.isBufferConsumed()) {
				currentRecordDeserializer.getCurrentBuffer().recycleBuffer();
				currentRecordDeserializer = null;
			}

			if (result.isFullRecord()) {
				StreamElement recordOrMark = deserializationDelegate.getInstance();

				if (recordOrMark.isWatermark()) {
					synchronized (lock) {
						// handle watermark
						statusWatermarkValve.inputWatermark(recordOrMark.asWatermark(), currentChannel);
						epochTracker.incRecordCount();
					}
					return true;
				} else if (recordOrMark.isStreamStatus()) {
					synchronized (lock) {
						// handle stream status
						statusWatermarkValve.inputStreamStatus(recordOrMark.asStreamStatus(), currentChannel);
						epochTracker.incRecordCount();
					}
					return true;
				} else if (recordOrMark.isLatencyMarker()) {
					synchronized (lock) {
						// handle latency marker
						streamOperator.processLatencyMarker(recordOrMark.asLatencyMarker());
						epochTracker.incRecordCount();
					}
					return true;
				} else {
					// now we can do the actual processing
					StreamRecord<IN> record = recordOrMark.asRecord();
					synchronized (lock) {
						numRecordsIn.inc();
						streamOperator.setKeyContextElement1(record);
						streamOperator.processElement(record);
						epochTracker.incRecordCount();
					}
					return true;
				}
			}
		}

		final BufferOrEvent bufferOrEvent = barrierHandler.getNextNonBlocked();
		if (bufferOrEvent != null) {
			if (bufferOrEvent.isBuffer()) {
				currentChannel = bufferOrEvent.getChannelIndex();
				currentRecordDeserializer = recordDeserializers[currentChannel];
				currentRecordDeserializer.setNextBuffer(bufferOrEvent.getBuffer());
			} else {// Event received
				final AbstractEvent event = bufferOrEvent.getEvent();
				if (event.getClass() != EndOfPartitionEvent.class) {
					throw new IOException("Unexpected event: " + event);
				}
			}
			return true;
		} else {
			isFinished = true;
			if (!barrierHandler.isEmpty()) {
				throw new IllegalStateException("Trailing data in checkpoint barrier handler.");
			}
			return false;
		}
	}

	public void cleanup() throws IOException {
		// clear the buffers first. this part should not ever fail
		for (RecordDeserializer<?> deserializer : recordDeserializers) {
			Buffer buffer = deserializer.getCurrentBuffer();
			if (buffer != null && !buffer.isRecycled()) {
				buffer.recycleBuffer();
			}
			deserializer.clear();
		}

		// cleanup the barrier handler resources
		barrierHandler.cleanup();
	}


	public CheckpointBarrierHandler getCheckpointBarrierHandlers() {
		return this.barrierHandler;
	}


	private class ForwardingValveOutputHandler implements StatusWatermarkValve.ValveOutputHandler {
		private final OneInputStreamOperator<IN, ?> operator;
		private final Object lock;

		private ForwardingValveOutputHandler(final OneInputStreamOperator<IN, ?> operator, final Object lock) {
			this.operator = checkNotNull(operator);
			this.lock = checkNotNull(lock);
		}

		@Override
		public void handleWatermark(Watermark watermark) {
			try {
				synchronized (lock) {
					watermarkGauge.setCurrentWatermark(watermark.getTimestamp());
					operator.processWatermark(watermark);
				}
			} catch (Exception e) {
				throw new RuntimeException("Exception occurred while processing valve output watermark: ", e);
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		public void handleStreamStatus(StreamStatus streamStatus) {
			try {
				synchronized (lock) {
					streamStatusMaintainer.toggleStreamStatus(streamStatus);
				}
			} catch (Exception e) {
				throw new RuntimeException("Exception occurred while processing valve output stream status: ", e);
			}
		}
	}

	public void resetInputChannelDeserializer(InputGate gate, int channelIndex) {
		int absoluteChannelIndex = this.inputGate.getAbsoluteChannelIndex(gate, channelIndex);

		recordDeserializers[absoluteChannelIndex].clear();
		barrierHandler.unblockChannelIfBlocked(absoluteChannelIndex);

	}
}
