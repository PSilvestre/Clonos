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
package org.apache.flink.runtime.causal.determinant;


import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.util.AbstractID;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

public class SimpleDeterminantEncodingStrategy implements DeterminantEncodingStrategy {
	/*
	According to google object pooling is not worth it anymore. Possible todo is to use a tiered pool of byte array lengths
	 */
	@Override
	public byte[] encode(Determinant determinant) {
		if (determinant.isOrderDeterminant()) return encodeOrderDeterminant(determinant.asOrderDeterminant());
		if (determinant.isRandomEmitDeterminant()) return encodeRandomEmitDeterminant(determinant.asRandomEmitDeterminant());
		if (determinant.isTimestampDeterminant()) return encodeTimestampDeterminant(determinant.asTimestampDeterminant());
		if (determinant.isRNGDeterminant()) return encodeRNGDeterminant(determinant.asRNGDeterminant());
		if (determinant.isBufferBuiltDeterminant()) return encodeBufferBuiltDeterminant(determinant.asBufferBuiltDeterminant());
		throw new UnknownDeterminantTypeException();
	}


	@Override
	public List<Determinant> decode(byte[] determinants) {
		List<Determinant> result = new LinkedList<>();
		ByteBuffer b = ByteBuffer.wrap(determinants);

		while (b.hasRemaining()) {
			result.add(decodeNext(b));
		}
		return result;
	}

	@Override
	public Determinant decodeNext(ByteBuffer b) {
		if(!b.hasRemaining())
			return null;
		byte tag = b.get();
		if (tag == Determinant.ORDER_DETERMINANT_TAG) return decodeOrderDeterminant(b);
		if (tag == Determinant.RANDOMEMIT_DETERMINANT_TAG) return decodeRandomEmitDeterminant(b);
		if (tag == Determinant.TIMESTAMP_DETERMINANT_TAG) return decodeTimestampDeterminant(b);
		if (tag == Determinant.RNG_DETERMINANT_TAG) return decodeRNGDeterminant(b);
		if (tag == Determinant.BUFFER_BUILT_TAG) return decodeBufferBuiltDeterminant(b);
		throw new CorruptDeterminantArrayException();
	}

	private Determinant decodeOrderDeterminant(ByteBuffer b) {
		return new OrderDeterminant(b.get());
	}

	private byte[] encodeOrderDeterminant(OrderDeterminant orderDeterminant) {
		byte[] bytes = new byte[2];
		//bytes[0] = Determinant.ORDER_DETERMINANT_TAG;
		bytes[1] = orderDeterminant.getChannel();
		return bytes;
	}

	private Determinant decodeTimestampDeterminant(ByteBuffer b) {
		return new TimestampDeterminant(b.getLong());
	}

	private byte[] encodeTimestampDeterminant(TimestampDeterminant timestampDeterminant) {
		byte[] bytes = new byte[1 + Long.BYTES];
		ByteBuffer b = ByteBuffer.wrap(bytes);
		b.put(Determinant.TIMESTAMP_DETERMINANT_TAG);
		b.putLong(timestampDeterminant.getTimestamp());
		return b.array();
	}

	private Determinant decodeRandomEmitDeterminant(ByteBuffer b) {
		return new RandomEmitDeterminant(b.get());
	}

	private byte[] encodeRandomEmitDeterminant(RandomEmitDeterminant randomEmitDeterminant) {
		byte[] bytes = new byte[2];
		bytes[0] = Determinant.RANDOMEMIT_DETERMINANT_TAG;
		bytes[1] = randomEmitDeterminant.getChannel();
		return bytes;
	}

	private Determinant decodeRNGDeterminant(ByteBuffer b) {
		return new RNGDeterminant(b.getInt());
	}

	private byte[] encodeRNGDeterminant(RNGDeterminant rngDeterminant) {
		byte[] bytes = new byte[1 + Integer.BYTES];
		ByteBuffer b = ByteBuffer.wrap(bytes);
		b.put(Determinant.RNG_DETERMINANT_TAG);
		b.putInt(rngDeterminant.getNumber());
		return b.array();
	}
	private Determinant decodeBufferBuiltDeterminant(ByteBuffer b) {
		long upper = b.getLong();
		long lower = b.getLong();
		byte index = b.get();
		int bytes = b.getInt();
		return new BufferBuiltDeterminant(new IntermediateDataSetID(new AbstractID(lower, upper)), index, bytes);
	}

	private byte[] encodeBufferBuiltDeterminant(BufferBuiltDeterminant asBufferBuiltDeterminant) {
		byte[] bytes = new byte[1 + 2 * Long.BYTES + 1 + Integer.BYTES];
		ByteBuffer b = ByteBuffer.wrap(bytes);
		b.put(Determinant.BUFFER_BUILT_TAG);
		b.putLong(asBufferBuiltDeterminant.getIntermediateDataSetID().getUpperPart());
		b.putLong(asBufferBuiltDeterminant.getIntermediateDataSetID().getLowerPart());
		b.put(asBufferBuiltDeterminant.getSubpartitionIndex());
		b.putInt(asBufferBuiltDeterminant.getNumberOfBytes());
		return b.array();

	}
}
