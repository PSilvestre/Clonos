/*
 *
 *
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 *
 *
 */

package org.apache.flink.runtime.causal.recovery;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Table;
import org.apache.flink.runtime.causal.determinant.*;
import org.apache.flink.runtime.causal.log.thread.SubpartitionThreadLogDelta;
import org.apache.flink.runtime.causal.log.vertex.VertexCausalLogDelta;
import org.apache.flink.runtime.event.InFlightLogRequestEvent;
import org.apache.flink.runtime.io.network.partition.PipelinedSubpartition;
import org.apache.flink.runtime.io.network.partition.ResultSubpartition;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.shaded.netty4.io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * In this state we do the actual process of recovery. Once done transition to {@link RunningState}
 * <p>
 * A downstream failure in this state does not matter, requests simply get added to the queue of unanswered requests.
 * Only when we finish our recovery do we answer those requests.
 * <p>
 * Upstream failures in this state mean that perhaps the upstream will not have sent all buffers. This means we must
 * resend
 * replay requests.
 */
public class ReplayingState extends AbstractState {

	private static final Logger LOG = LoggerFactory.getLogger(ReplayingState.class);

	DeterminantEncoder determinantEncoder;

	ByteBuf mainThreadRecoveryBuffer;

	List<Thread> recoveryThreads;

	Determinant nextDeterminant;


	public ReplayingState(RecoveryManager context, VertexCausalLogDelta recoveryDeterminants) {
		super(context);

		LOG.info("Entered replaying state with delta: {}", recoveryDeterminants);
		determinantEncoder = context.jobCausalLog.getDeterminantEncoder();

		createSubpartitionRecoveryThreads(recoveryDeterminants);
		for (Thread t : recoveryThreads)
			t.start();

		if (recoveryDeterminants.getMainThreadDelta() != null)
			this.mainThreadRecoveryBuffer = recoveryDeterminants.getMainThreadDelta().getRawDeterminants();
	}

	public void executeEnter() {
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		context.readyToReplayFuture.complete(null);//allow task to start running
	}

	private void createSubpartitionRecoveryThreads(VertexCausalLogDelta recoveryDeterminants) {

		recoveryThreads = new LinkedList<>();

		for (IntermediateResultPartitionID partID : recoveryDeterminants.getPartitionDeltas().keySet())
			for (Integer subpartID : recoveryDeterminants.getPartitionDeltas().get(partID).keySet()) {

				ByteBuf recoveryBuffer =
					recoveryDeterminants.getPartitionDeltas().get(partID).get(subpartID).getRawDeterminants();
				PipelinedSubpartition subpartition = context.subpartitionTable.get(partID, subpartID);

				Thread t = new SubpartitionRecoveryThread(recoveryBuffer, subpartition, context, partID, subpartID);
				recoveryThreads.add(t);

				LOG.info("Created recovery thread for Partition {} subpartition index {} with buffer {}", partID,
					subpartID, recoveryBuffer);
			}
	}


	@Override
	public void notifyNewInputChannel(RemoteInputChannel remoteInputChannel, int consumedSubpartitionIndex,
									  int numberOfBuffersRemoved) {
		//we got notified of a new input channel while we were replaying
		//This means that  we now have to wait for the upstream to finish recovering before we do.
		//Furthermore, we have to resend the inflight log request, and ask to skip X buffers

		LOG.info("Got notified of new input channel event, while in state " + this.getClass() + " requesting upstream " +
			"to replay and skip numberOfBuffersRemoved");
		IntermediateResultPartitionID id = remoteInputChannel.getPartitionId().getPartitionId();
		try {
			remoteInputChannel.sendTaskEvent(new InFlightLogRequestEvent(id, consumedSubpartitionIndex,
				context.epochProvider.getCurrentEpochID(), numberOfBuffersRemoved));
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}


	//===================

	@Override
	public int replayRandomInt() {
		if (!(nextDeterminant instanceof RNGDeterminant))
			throw new RuntimeException("Unexpected Determinant type: Expected RNG, but got: " + nextDeterminant);
		RNGDeterminant toReturn = (RNGDeterminant) nextDeterminant;
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		return toReturn.getNumber();
	}

	@Override
	public byte replayNextChannel() {
		if (!(nextDeterminant instanceof OrderDeterminant))
			throw new RuntimeException("Unexpected Determinant type: Expected Order, but got: " + nextDeterminant);

		OrderDeterminant toReturn = (OrderDeterminant) nextDeterminant;
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		return toReturn.getChannel();
	}

	@Override
	public long replayNextTimestamp() {
		if (!(nextDeterminant instanceof TimestampDeterminant))
			throw new RuntimeException("Unexpected Determinant type: Expected Timestamp, but got: " + nextDeterminant);

		TimestampDeterminant toReturn = (TimestampDeterminant) nextDeterminant;
		prepareNext();
		if (nextDeterminant == null)
			finishReplaying();
		return toReturn.getTimestamp();
	}

	@Override
	public void checkAsyncEvent() {
		while (nextDeterminant instanceof AsyncDeterminant) {
			AsyncDeterminant asyncDeterminant = (AsyncDeterminant) nextDeterminant;
			int currentRecordCount = context.recordCountProvider.getRecordCount();
			if (currentRecordCount > asyncDeterminant.getRecordCount())
				throw new RuntimeException("Current record count is beyond the determinants record count. Current: " + currentRecordCount + ", determinant: " + asyncDeterminant.getRecordCount());
			LOG.debug("Current: " + currentRecordCount + ", determinant: " + asyncDeterminant.getRecordCount());
			if (context.recordCountProvider.getRecordCount() == asyncDeterminant.getRecordCount()) {
				LOG.debug("We are at the same point in the stream, with record count: {}",
					asyncDeterminant.getRecordCount());
				//Prepare next first, because the async event, in being processed, may require determinants
				prepareNext();
				asyncDeterminant.process(context);
				if (nextDeterminant == null)
					finishReplaying();

			} else
				break;
		}
	}

	private void prepareNext() {
		nextDeterminant = null;
		if (mainThreadRecoveryBuffer != null && mainThreadRecoveryBuffer.isReadable())
			nextDeterminant = determinantEncoder.decodeNext(mainThreadRecoveryBuffer);
	}

	private void finishReplaying() {

		//Safety check that recovery brought us to the exact same causal log state as pre-failure
		assert mainThreadRecoveryBuffer.capacity() == context.jobCausalLog.mainThreadLogLength();

		if (mainThreadRecoveryBuffer != null)
			mainThreadRecoveryBuffer.release();

		LOG.info("Finished recovering main thread! Transitioning to RunningState!");
		context.setState(new RunningState(context));
	}

	@Override
	public String toString() {
		return "ReplayingState{}";
	}

private static class SubpartitionRecoveryThread extends Thread {
	private final PipelinedSubpartition pipelinedSubpartition;
	private final ByteBuf recoveryBuffer;
	private final DeterminantEncoder determinantEncoder;
	private final RecoveryManager context;
	private final IntermediateResultPartitionID partitionID;
	private final int index;

	public SubpartitionRecoveryThread(ByteBuf recoveryBuffer, PipelinedSubpartition pipelinedSubpartition,
									  RecoveryManager context, IntermediateResultPartitionID partitionID, int index) {
		this.recoveryBuffer = recoveryBuffer;
		this.pipelinedSubpartition = pipelinedSubpartition;
		this.determinantEncoder = context.jobCausalLog.getDeterminantEncoder();
		this.context = context;
		this.partitionID = partitionID;
		this.index = index;

	}

	@Override
	public void run() {
		context.numberOfRecoveringSubpartitions.incrementAndGet();
		//1. Netty has been told that there is no data.
		pipelinedSubpartition.setIsRecoveringSubpartitionInFlightState(true);
		//2. Rebuild in-fligh log and subpartition state
		while (recoveryBuffer.isReadable()) {

			Determinant determinant = determinantEncoder.decodeNext(recoveryBuffer);

			if (!(determinant instanceof BufferBuiltDeterminant))
				throw new RuntimeException("Subpartition has corrupt recovery buffer, expected buffer built, got: " + determinant);
			BufferBuiltDeterminant bufferBuiltDeterminant = (BufferBuiltDeterminant) determinant;

			LOG.debug("Requesting to build and log buffer with {} bytes", bufferBuiltDeterminant.getNumberOfBytes());
			pipelinedSubpartition.buildAndLogBuffer(bufferBuiltDeterminant.getNumberOfBytes());
		}
		LOG.info("Done recovering pipelined subpartition");
		//Safety check that recovery brought us to the exact same state as pre-failure
		assert recoveryBuffer.capacity() == context.jobCausalLog.subpartitionLogLength(partitionID, index);

		// If there is a replay request, we have to prepare it, before setting isRecovering to true
		InFlightLogRequestEvent unansweredRequest =
			context.unansweredInFlighLogRequests.remove(partitionID, index);
		LOG.info("Checking for unanswered inflight request for this subpartition.");
		if (unansweredRequest != null) {
			LOG.info("There is an unanswered replay request for this subpartition.");
			pipelinedSubpartition.requestReplay(unansweredRequest.getCheckpointId(),
				unansweredRequest.getNumberOfBuffersToSkip());
		}

		//3. Tell netty to restart requesting buffers.
		pipelinedSubpartition.setIsRecoveringSubpartitionInFlightState(false);
		pipelinedSubpartition.notifyDataAvailable();
		recoveryBuffer.release();
		context.numberOfRecoveringSubpartitions.decrementAndGet();
		LOG.info("Subpartition is free to restart sending buffers.");

	}
}

}
