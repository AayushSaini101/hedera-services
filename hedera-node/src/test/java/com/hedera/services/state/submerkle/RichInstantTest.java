package com.hedera.services.state.submerkle;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hederahashgraph.api.proto.java.Timestamp;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.Mockito.inOrder;

class RichInstantTest {
	private static final long seconds = 1_234_567L;
	private static final int nanos = 890;

	private RichInstant subject;

	@BeforeEach
	void setup() {
		subject = new RichInstant(seconds, nanos);
	}

	@Test
	void serializeWorks() throws IOException {
		final var out = mock(SerializableDataOutputStream.class);
		final var inOrder = inOrder(out);

		subject.serialize(out);

		inOrder.verify(out).writeLong(seconds);
		inOrder.verify(out).writeInt(nanos);
	}

	@Test
	void factoryWorks() throws IOException {
		final var in = mock(SerializableDataInputStream.class);
		given(in.readLong()).willReturn(seconds);
		given(in.readInt()).willReturn(nanos);

		final var readSubject = RichInstant.from(in);

		assertEquals(subject, readSubject);
	}

	@Test
	void beanWorks() {
		assertEquals(subject, new RichInstant(subject.getSeconds(), subject.getNanos()));
	}

	@Test
	void viewWorks() {
		final var grpc = Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();

		assertEquals(grpc, subject.toGrpc());
	}

	@Test
	void knowsIfMissing() {
		assertFalse(subject.isMissing());
		assertTrue(RichInstant.MISSING_INSTANT.isMissing());
	}

	@Test
	void toStringWorks() {
		assertEquals(
				"RichInstant{seconds=" + seconds + ", nanos=" + nanos + "}",
				subject.toString());
	}

	@Test
	void factoryWorksForMissing() {
		assertEquals(RichInstant.MISSING_INSTANT, RichInstant.fromGrpc(Timestamp.getDefaultInstance()));
		assertEquals(subject, RichInstant.fromGrpc(subject.toGrpc()));
	}

	@Test
	void objectContractWorks() {
		final var one = subject;
		final var two = new RichInstant(seconds - 1, nanos - 1);
		final var three = new RichInstant(subject.getSeconds(), subject.getNanos());

		assertNotEquals(null, one);
		assertNotEquals(new Object(), one);
		assertNotEquals(one, two);
		assertEquals(one, three);

		assertEquals(one.hashCode(), three.hashCode());
		assertNotEquals(one.hashCode(), two.hashCode());
	}

	@Test
	void orderingWorks() {
		assertTrue(subject.isAfter(new RichInstant(seconds - 1, nanos)));
		assertTrue(subject.isAfter(new RichInstant(seconds, nanos - 1)));
		assertFalse(subject.isAfter(new RichInstant(seconds, nanos + 1)));
	}

	@Test
	void javaFactoryWorks() {
		assertEquals(subject, RichInstant.fromJava(Instant.ofEpochSecond(subject.getSeconds(), subject.getNanos())));
	}

	@Test
	void javaViewWorks() {
		assertEquals(Instant.ofEpochSecond(subject.getSeconds(), subject.getNanos()), subject.toJava());
	}
}
