package com.hedera.services.state.merkle;

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

import com.swirlds.blob.BinaryObject;
import com.swirlds.blob.BinaryObjectStore;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.verify;

class MerkleOptionalBlobTest {
	private static final byte[] stuff = "abcdefghijklmnopqrstuvwxyz".getBytes();
	private static final byte[] newStuff = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes();

	private static final String readableStuffDelegate = "<me>";
	private static final Hash stuffDelegateHash = new Hash(new byte[] {
			(byte) 0xf0, (byte) 0xe1, (byte) 0xd2, (byte) 0xc3,
			(byte) 0xf4, (byte) 0xe5, (byte) 0xd6, (byte) 0xc7,
			(byte) 0xf8, (byte) 0xe9, (byte) 0xda, (byte) 0xcb,
			(byte) 0xfc, (byte) 0xed, (byte) 0xde, (byte) 0xcf,
			(byte) 0xf0, (byte) 0xe1, (byte) 0xd2, (byte) 0xc3,
			(byte) 0xf4, (byte) 0xe5, (byte) 0xd6, (byte) 0xc7,
			(byte) 0xf8, (byte) 0xe9, (byte) 0xda, (byte) 0xcb,
			(byte) 0xfc, (byte) 0xed, (byte) 0xde, (byte) 0xcf,
			(byte) 0xf0, (byte) 0xe1, (byte) 0xd2, (byte) 0xc3,
			(byte) 0xf4, (byte) 0xe5, (byte) 0xd6, (byte) 0xc7,
			(byte) 0xf8, (byte) 0xe9, (byte) 0xda, (byte) 0xcb,
			(byte) 0xfc, (byte) 0xed, (byte) 0xde, (byte) 0xcf,
	});

	private BinaryObjectStore blobStore;
	private BinaryObject newDelegate;
	private BinaryObject stuffDelegate;
	private BinaryObject newStuffDelegate;

	private MerkleOptionalBlob subject;

	@BeforeEach
	void setup() {
		newDelegate = mock(BinaryObject.class);
		stuffDelegate = mock(BinaryObject.class);
		newStuffDelegate = mock(BinaryObject.class);
		given(stuffDelegate.toString()).willReturn(readableStuffDelegate);
		given(stuffDelegate.getHash()).willReturn(stuffDelegateHash);
		blobStore = mock(BinaryObjectStore.class);
		given(blobStore.put(argThat((byte[] bytes) -> Arrays.equals(bytes, stuff)))).willReturn(stuffDelegate);
		given(blobStore.put(argThat((byte[] bytes) -> Arrays.equals(bytes, newStuff)))).willReturn(newStuffDelegate);
		given(blobStore.get(stuffDelegate)).willReturn(stuff);

		MerkleOptionalBlob.blobSupplier = () -> newDelegate;
		MerkleOptionalBlob.blobStoreSupplier = () -> blobStore;

		subject = new MerkleOptionalBlob(stuff);
	}

	@AfterEach
	void cleanup() {
		MerkleOptionalBlob.blobSupplier = BinaryObject::new;
		MerkleOptionalBlob.blobStoreSupplier = BinaryObjectStore::getInstance;
	}

	@Test
	void modifyWorksWithNonEmpty() {
		subject.modify(newStuff);

		verify(stuffDelegate).release();
		assertEquals(newStuffDelegate, subject.getDelegate());
	}

	@Test
	void modifyWorksWithEmpty() {
		subject = new MerkleOptionalBlob();

		subject.modify(newStuff);

		verify(stuffDelegate, never()).release();
		assertEquals(newStuffDelegate, subject.getDelegate());
	}

	@Test
	void getDataWorksWithStuff() {
		assertArrayEquals(stuff, subject.getData());
	}

	@Test
	void getDataWorksWithNoStuff() {
		assertArrayEquals(MerkleOptionalBlob.NO_DATA, new MerkleOptionalBlob().getData());
	}

	@Test
	void emptyHashAsExpected() {
		final var defaultSubject = new MerkleOptionalBlob();

		assertEquals(MerkleOptionalBlob.MISSING_DELEGATE_HASH, defaultSubject.getHash());
	}

	@Test
	void stuffHashDelegates() {
		assertEquals(stuffDelegateHash, subject.getHash());
	}

	@Test
	void merkleMethodsWork() {
		assertEquals(MerkleOptionalBlob.MERKLE_VERSION, subject.getVersion());
		assertEquals(MerkleOptionalBlob.RUNTIME_CONSTRUCTABLE_ID, subject.getClassId());
		assertTrue(subject.isLeaf());
		assertThrows(UnsupportedOperationException.class, () -> subject.setHash(null));
		assertDoesNotThrow(() -> subject.serializeAbbreviated(null));
	}

	@Test
	void deserializeWorksWithEmpty() throws IOException {
		final var in = mock(SerializableDataInputStream.class);
		final var defaultSubject = new MerkleOptionalBlob();
		given(in.readBoolean()).willReturn(false);

		defaultSubject.deserialize(in, MerkleOptionalBlob.MERKLE_VERSION);

		verify(newDelegate, never()).deserialize(in, MerkleOptionalBlob.MERKLE_VERSION);
	}

	@Test
	void serializeWorksWithEmpty() throws IOException {
		final var out = mock(SerializableDataOutputStream.class);
		final var defaultSubject = new MerkleOptionalBlob();

		defaultSubject.serialize(out);

		verify(out).writeBoolean(false);
	}

	@Test
	void serializeWorksWithDelegate() throws IOException {
		final var out = mock(SerializableDataOutputStream.class);
		final var inOrder = inOrder(out, stuffDelegate);

		subject.serialize(out);

		inOrder.verify(out).writeBoolean(true);
		inOrder.verify(stuffDelegate).serialize(out);
	}

	@Test
	void deserializeAbbrevWorksWithDelegate() {
		final var in = mock(SerializableDataInputStream.class);

		subject.deserializeAbbreviated(in, stuffDelegateHash, MerkleOptionalBlob.MERKLE_VERSION);

		verify(newDelegate).deserializeAbbreviated(in, stuffDelegateHash, MerkleOptionalBlob.MERKLE_VERSION);
	}

	@Test
	void deserializeAbbrevWorksWithoutDelegate() {
		final var in = mock(SerializableDataInputStream.class);

		subject.deserializeAbbreviated(in, MerkleOptionalBlob.MISSING_DELEGATE_HASH, MerkleOptionalBlob.MERKLE_VERSION);

		assertEquals(MerkleOptionalBlob.NO_DATA, subject.getData());
		assertEquals(MerkleOptionalBlob.MISSING_DELEGATE, subject.getDelegate());
	}

	@Test
	void deserializeWorksWithDelegate() throws IOException {
		final var in = mock(SerializableDataInputStream.class);
		final var defaultSubject = new MerkleOptionalBlob();
		given(in.readBoolean()).willReturn(true);

		defaultSubject.deserialize(in, MerkleOptionalBlob.MERKLE_VERSION);

		verify(newDelegate).deserialize(in, MerkleOptionalBlob.MERKLE_VERSION);
	}

	@Test
	void toStringWorks() {
		assertEquals(
				"MerkleOptionalBlob{delegate=" + readableStuffDelegate + "}",
				subject.toString());
		assertEquals(
				"MerkleOptionalBlob{delegate=null}",
				new MerkleOptionalBlob().toString());
	}

	@Test
	void copyWorks() {
		given(stuffDelegate.copy()).willReturn(stuffDelegate);

		final var subjectCopy = subject.copy();

		assertNotSame(subject, subjectCopy);
		assertEquals(subject, subjectCopy);
		assertTrue(subject.isImmutable());
	}

	@Test
	void deleteDelegatesIfAppropos() {
		subject.release();

		verify(stuffDelegate).release();
	}

	@Test
	void doesntDelegateIfMissing() {
		subject = new MerkleOptionalBlob();

		subject.release();

		verify(stuffDelegate, never()).release();
	}

	@Test
	void objectContractMet() {
		final var one = new MerkleOptionalBlob();
		final var two = new MerkleOptionalBlob(stuff);
		final var three = new MerkleOptionalBlob(stuff);
		final var twoRef = two;

		final var equalsForcedCallResult = one.equals(null);
		assertFalse(equalsForcedCallResult);
		assertNotEquals(one, new Object());
		assertNotEquals(two, one);
		assertEquals(two, twoRef);
		assertEquals(two, three);

		assertNotEquals(one.hashCode(), two.hashCode());
		assertEquals(two.hashCode(), three.hashCode());
	}
}
