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

package org.apache.flink.runtime.causal;


import org.apache.flink.runtime.causal.recovery.IRecoveryManager;

import static org.apache.flink.runtime.causal.recovery.RecoveryManager.NO_RECORD_COUNT_TARGET;

/**
 * All interaction with the record counter should be done inside the checkpoint lock.
 */
public final class RecordCounterImpl implements RecordCounter {

	private int recordCount;

	private int recordCountTarget;

	private  IRecoveryManager recoveryManager;

	public RecordCounterImpl() {
		recordCount = 0;
		recordCountTarget = NO_RECORD_COUNT_TARGET;
	}

	@Override
	public final int getRecordCount() {
		return recordCount;
	}

	@Override
	public final void incRecordCount() {

		//Before returning control to the caller, check if we first should execute async nondeterministic event
		while(recordCount == recordCountTarget) {
			recordCountTarget = NO_RECORD_COUNT_TARGET;
			recoveryManager.triggerAsyncEvent();
		}

		recordCount++;
	}

	@Override
	public final void resetRecordCount() {
		recordCount = 0;
	}

	@Override
	public void setRecordCountTarget(int target) {
		this.recordCountTarget = target;
	}

	@Override
	public void setRecoveryManager(IRecoveryManager recoveryManager) {
		this.recoveryManager = recoveryManager;
	}

}
