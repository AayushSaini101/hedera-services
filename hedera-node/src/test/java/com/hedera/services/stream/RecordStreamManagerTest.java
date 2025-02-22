package com.hedera.services.stream;

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

import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.stats.MiscRunningAvgs;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.stream.MultiStream;
import com.swirlds.common.stream.QueueThreadObjectStream;
import org.apache.commons.lang3.RandomUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Queue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(LogCaptureExtension.class)
public class RecordStreamManagerTest {
	private static final Platform platform = mock(Platform.class);

	private static final MiscRunningAvgs runningAvgsMock = mock(MiscRunningAvgs.class);

	private static final long recordsLogPeriod = 5;
	private static final int recordStreamQueueCapacity = 100;
	private static final String recordStreamDir = "recordStreamTest/record0.0.3";

	private static final String INITIALIZE_NOT_NULL = "after initialization, the instance should not be null";
	private static final String INITIALIZE_QUEUE_EMPTY = "after initialization, hash queue should be empty";
	private static final String UNEXPECTED_VALUE = "unexpected value";

	private static RecordStreamManager disableStreamingInstance;
	private static RecordStreamManager enableStreamingInstance;

	public static final Hash INITIAL_RANDOM_HASH = new Hash(RandomUtils.nextBytes(DigestType.SHA_384.digestLength()));

	private static final MultiStream<RecordStreamObject> multiStreamMock = mock(MultiStream.class);
	private static final QueueThreadObjectStream<RecordStreamObject> writeQueueThreadMock =
			mock(QueueThreadObjectStream.class);
	private static final RecordStreamManager RECORD_STREAM_MANAGER = new RecordStreamManager(
			multiStreamMock, writeQueueThreadMock, runningAvgsMock);

	private static NodeLocalProperties disabledProps;
	private static NodeLocalProperties enabledProps;

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private RecordStreamManager recordStreamManager;

	@BeforeAll
	public static void init() throws Exception {
		given(platform.getSelfId()).willReturn(new NodeId(false, 0L));
		disabledProps = mock(NodeLocalProperties.class);
		given(disabledProps.isRecordStreamEnabled()).willReturn(false);
		enabledProps = mock(NodeLocalProperties.class);
		given(enabledProps.isRecordStreamEnabled()).willReturn(true);
		configProps(disabledProps);
		configProps(enabledProps);

		disableStreamingInstance = new RecordStreamManager(
				platform,
				runningAvgsMock,
				disabledProps,
				recordStreamDir,
				INITIAL_RANDOM_HASH);
		enableStreamingInstance = new RecordStreamManager(
				platform,
				runningAvgsMock,
				enabledProps,
				recordStreamDir,
				INITIAL_RANDOM_HASH);
	}

	private static void configProps(NodeLocalProperties props) {
		given(props.recordLogDir()).willThrow(IllegalStateException.class);
		given(props.recordLogPeriod()).willReturn(recordsLogPeriod);
		given(props.recordStreamQueueCapacity()).willReturn(recordStreamQueueCapacity);
	}

	@Test
	void initializeTest() {
		assertNull(disableStreamingInstance.getStreamFileWriter(),
				"When recordStreaming is disabled, streamFileWriter instance should be null");
		assertNotNull(disableStreamingInstance.getMultiStream(), INITIALIZE_NOT_NULL);
		assertNotNull(disableStreamingInstance.getHashCalculator(), INITIALIZE_NOT_NULL);
		assertEquals(0, disableStreamingInstance.getHashQueueSize(), INITIALIZE_QUEUE_EMPTY);
		assertEquals(0, disableStreamingInstance.getWriteQueueSize(), INITIALIZE_QUEUE_EMPTY);

		assertNotNull(enableStreamingInstance.getStreamFileWriter(),
				"When recordStreaming is enabled, streamFileWriter instance should not be null");
		assertNotNull(enableStreamingInstance.getMultiStream(), INITIALIZE_NOT_NULL);
		assertNotNull(enableStreamingInstance.getHashCalculator(), INITIALIZE_NOT_NULL);
		assertEquals(0, enableStreamingInstance.getHashQueueSize(), INITIALIZE_QUEUE_EMPTY);
		assertEquals(0, enableStreamingInstance.getWriteQueueSize(), INITIALIZE_QUEUE_EMPTY);
	}

	@Test
	void setInitialHashTest() {
		RECORD_STREAM_MANAGER.setInitialHash(INITIAL_RANDOM_HASH);
		verify(multiStreamMock).setRunningHash(INITIAL_RANDOM_HASH);
		assertEquals(INITIAL_RANDOM_HASH, RECORD_STREAM_MANAGER.getInitialHash(), "initialHash is not set");
	}

	@Test
	void warnsOnInterruptedStreaming() {
		// setup:
		final var mockQueue = mock(Queue.class);

		given(writeQueueThreadMock.getQueue()).willReturn(mockQueue);
		recordStreamManager = new RecordStreamManager(multiStreamMock, writeQueueThreadMock, runningAvgsMock);

		willThrow(RuntimeException.class).given(multiStreamMock).addObject(any());

		// when:
		recordStreamManager.addRecordStreamObject(new RecordStreamObject());

		// then:
		assertThat(logCaptor.warnLogs(), contains(Matchers.startsWith("Unhandled exception while streaming")));
	}

	@Test
	void addRecordStreamObjectTest() {
		// setup:
		final var mockQueue = mock(Queue.class);
		recordStreamManager = new RecordStreamManager(
				multiStreamMock, writeQueueThreadMock, runningAvgsMock);
		assertFalse(recordStreamManager.getInFreeze(),
				"inFreeze should be false after initialization");
		final int recordsNum = 10;
		for (int i = 0; i < recordsNum; i++) {
			RecordStreamObject recordStreamObject = mock(RecordStreamObject.class);
			when(writeQueueThreadMock.getQueue()).thenReturn(mockQueue);
			given(mockQueue.size()).willReturn(i);
			recordStreamManager.addRecordStreamObject(recordStreamObject);
			verify(multiStreamMock).addObject(recordStreamObject);
			verify(runningAvgsMock).writeQueueSizeRecordStream(i);
			// multiStream should not be closed after adding it
			verify(multiStreamMock, never()).close();
			assertFalse(recordStreamManager.getInFreeze(),
					"inFreeze should be false after adding the records");
		}
		// set inFreeze to be true
		recordStreamManager.setInFreeze(true);
		assertTrue(recordStreamManager.getInFreeze(),
				"inFreeze should be true");
		// add an object after inFreeze is true
		RecordStreamObject objectAfterFreeze = mock(RecordStreamObject.class);

		given(mockQueue.size()).willReturn(recordsNum);
		when(writeQueueThreadMock.getQueue()).thenReturn(mockQueue);

		recordStreamManager.addRecordStreamObject(objectAfterFreeze);
		// after frozen, when adding object to the RecordStreamManager, multiStream.add(object) should not be called
		verify(multiStreamMock, never()).addObject(objectAfterFreeze);
		// multiStreamMock should be closed when inFreeze is set to be true
		verify(multiStreamMock).close();
		// should get recordStream queue size and set to runningAvgs
		verify(runningAvgsMock).writeQueueSizeRecordStream(recordsNum);
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void setStartWriteAtCompleteWindowTest(boolean startWriteAtCompleteWindow) {
		enableStreamingInstance.setStartWriteAtCompleteWindow(startWriteAtCompleteWindow);
		assertEquals(startWriteAtCompleteWindow,
				enableStreamingInstance.getStreamFileWriter().getStartWriteAtCompleteWindow(), UNEXPECTED_VALUE);
	}

	@Test
	void setInFreezeTest() {
		MultiStream<RecordStreamObject> multiStreamMock = mock(MultiStream.class);
		recordStreamManager = new RecordStreamManager(multiStreamMock, writeQueueThreadMock, runningAvgsMock);

		recordStreamManager.setInFreeze(false);
		assertFalse(recordStreamManager.getInFreeze());

		recordStreamManager.setInFreeze(true);

		assertTrue(recordStreamManager.getInFreeze());
		// multiStreamMock should be closed when inFreeze is true;
		verify(multiStreamMock).close();
		// and:
		assertThat(logCaptor.infoLogs(), contains(
				"RecordStream inFreeze is set to be false",
				"RecordStream inFreeze is set to be true"));
	}
}
