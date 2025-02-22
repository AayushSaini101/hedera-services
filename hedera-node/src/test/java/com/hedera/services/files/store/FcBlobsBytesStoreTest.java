package com.hedera.services.files.store;

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

import com.hedera.services.state.merkle.MerkleBlobMeta;
import com.hedera.services.state.merkle.MerkleOptionalBlob;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

class FcBlobsBytesStoreTest {
	private static final byte[] aData = "BlobA".getBytes();
	private static final byte[] bData = "BlobB".getBytes();
	private static final String pathA = "pathA";
	private static final MerkleBlobMeta aMeta = new MerkleBlobMeta(pathA);
	private static final MerkleBlobMeta bMeta = new MerkleBlobMeta("pathB");

	private MerkleOptionalBlob blobA, blobB;
	private Function<byte[], MerkleOptionalBlob> blobFactory;
	private FCMap<MerkleBlobMeta, MerkleOptionalBlob> pathedBlobs;

	private FcBlobsBytesStore subject;

	@BeforeEach
	private void setup() {
		pathedBlobs = mock(FCMap.class);
		blobFactory = mock(Function.class);

		givenMockBlobs();
		given(blobFactory.apply(any()))
				.willReturn(blobA)
				.willReturn(blobB);

		subject = new FcBlobsBytesStore(blobFactory, () -> pathedBlobs);
	}

	@Test
	void delegatesClear() {
		subject.clear();

		verify(pathedBlobs).clear();
	}

	@Test
	void delegatesRemoveOfMissing() {
		given(pathedBlobs.remove(aMeta)).willReturn(null);

		assertNull(subject.remove(pathA));
	}

	@Test
	void delegatesRemoveAndReturnsNull() {
		given(pathedBlobs.remove(aMeta)).willReturn(blobA);

		assertNull(subject.remove(pathA));
	}

	@Test
	void delegatesPutUsingGetForModifyIfExtantBlob() {
		given(pathedBlobs.containsKey(aMeta)).willReturn(true);
		given(pathedBlobs.getForModify(aMeta)).willReturn(blobA);

		final var oldBytes = subject.put(pathA, aData);

		verify(pathedBlobs).containsKey(aMeta);
		verify(pathedBlobs).getForModify(aMeta);
		verify(blobA).modify(aData);

		assertNull(oldBytes);
	}

	@Test
	void delegatesPutUsingGetAndFactoryIfNewBlob() {
		final var keyCaptor = ArgumentCaptor.forClass(MerkleBlobMeta.class);
		final var valueCaptor = ArgumentCaptor.forClass(MerkleOptionalBlob.class);
		given(pathedBlobs.containsKey(aMeta)).willReturn(false);

		final var oldBytes = subject.put(pathA, aData);

		verify(pathedBlobs).containsKey(aMeta);
		verify(pathedBlobs).put(keyCaptor.capture(), valueCaptor.capture());

		assertEquals(aMeta, keyCaptor.getValue());
		assertSame(blobA, valueCaptor.getValue());
		assertNull(oldBytes);
	}

	@Test
	void propagatesNullFromGet() {
		given(pathedBlobs.get(argThat(sk -> ((MerkleBlobMeta) sk).getPath().equals(pathA)))).willReturn(null);

		assertNull(subject.get(pathA));
	}

	@Test
	void delegatesGet() {
		given(pathedBlobs.get(argThat(sk -> ((MerkleBlobMeta) sk).getPath().equals(pathA)))).willReturn(blobA);

		assertArrayEquals(aData, subject.get(pathA));
	}

	@Test
	void delegatesContainsKey() {
		given(pathedBlobs.containsKey(argThat(sk -> ((MerkleBlobMeta) sk).getPath().equals(pathA))))
				.willReturn(true);

		assertTrue(subject.containsKey(pathA));
	}

	@Test
	void delegatesIsEmpty() {
		given(pathedBlobs.isEmpty()).willReturn(true);

		assertTrue(subject.isEmpty());
		verify(pathedBlobs).isEmpty();
	}

	@Test
	void delegatesSize() {
		given(pathedBlobs.size()).willReturn(123);

		assertEquals(123, subject.size());
	}

	@Test
	void delegatesEntrySet() {
		final Set<Entry<MerkleBlobMeta, MerkleOptionalBlob>> blobEntries = Set.of(
				new AbstractMap.SimpleEntry<>(aMeta, blobA),
				new AbstractMap.SimpleEntry<>(bMeta, blobB));
		given(pathedBlobs.entrySet()).willReturn(blobEntries);

		final var entries = subject.entrySet();

		assertEquals(
				"pathA->BlobA, pathB->BlobB",
				entries
						.stream()
						.sorted(Comparator.comparing(Entry::getKey))
						.map(entry -> String.format("%s->%s", entry.getKey(), new String(entry.getValue())))
						.collect(Collectors.joining(", "))
		);
	}

	private void givenMockBlobs() {
		blobA = mock(MerkleOptionalBlob.class);
		blobB = mock(MerkleOptionalBlob.class);

		given(blobA.getData()).willReturn(aData);
		given(blobB.getData()).willReturn(bData);
	}

	@Test
	void putDeletesReplacedValueIfNoCopyIsHeld() {
		final FCMap<MerkleBlobMeta, MerkleOptionalBlob> blobs = new FCMap<>();
		blobs.put(at("path"), new MerkleOptionalBlob("FIRST".getBytes()));

		final var replaced = blobs.put(at("path"), new MerkleOptionalBlob("SECOND".getBytes()));

		assertTrue(replaced.getDelegate().isReleased());
	}

	@Test
	void putDoesNotDeleteReplacedValueIfCopyIsHeld() {
		final FCMap<MerkleBlobMeta, MerkleOptionalBlob> blobs = new FCMap<>();
		blobs.put(at("path"), new MerkleOptionalBlob("FIRST".getBytes()));

		final var copy = blobs.copy();
		final var replaced = copy.put(at("path"), new MerkleOptionalBlob("SECOND".getBytes()));

		assertFalse(replaced.getDelegate().isReleased());
	}

	private MerkleBlobMeta at(final String key) {
		return new MerkleBlobMeta(key);
	}
}
