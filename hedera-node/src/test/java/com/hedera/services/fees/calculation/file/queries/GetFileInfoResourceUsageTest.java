package com.hedera.services.fees.calculation.file.queries;

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

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.usage.file.ExtantFileContext;
import com.hedera.services.usage.file.FileOpsUsage;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FileGetInfoQuery;
import com.hederahashgraph.api.proto.java.FileGetInfoResponse;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static com.hedera.test.utils.IdUtils.asFile;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hederahashgraph.api.proto.java.ResponseType.COST_ANSWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GetFileInfoResourceUsageTest {
	long expiry = 1_234_567L;
	long size = 123;
	String memo = "Ok whatever";
	FileID target = asFile("0.0.123");
	StateView view;
	FileOpsUsage fileOpsUsage;
	GetFileInfoResourceUsage subject;
	Key wacl = TxnHandlingScenario.MISC_FILE_WACL_KT.asKey();
	FileGetInfoResponse.FileInfo targetInfo = FileGetInfoResponse.FileInfo.newBuilder()
			.setExpirationTime(Timestamp.newBuilder().setSeconds(expiry).build())
			.setSize(size)
			.setMemo(memo)
			.setKeys(wacl.getKeyList())
			.build();

	@BeforeEach
	private void setup() throws Throwable {
		fileOpsUsage = mock(FileOpsUsage.class);

		view = mock(StateView.class);

		subject = new GetFileInfoResourceUsage(fileOpsUsage);
	}

	@Test
	void returnsDefaultSchedulesOnMissing() {
		Query answerOnlyQuery = fileInfoQuery(target, ANSWER_ONLY);

		given(view.infoForFile(any())).willReturn(Optional.empty());

		// then:
		assertSame(FeeData.getDefaultInstance(), subject.usageGiven(answerOnlyQuery, view));
	}

	@Test
	void invokesEstimatorAsExpectedForType() {
		// setup:
		FeeData expected = mock(FeeData.class);
		// and:
		ArgumentCaptor<ExtantFileContext> captor = ArgumentCaptor.forClass(ExtantFileContext.class);
		// and:
		Query answerOnlyQuery = fileInfoQuery(target, ANSWER_ONLY);

		given(view.infoForFile(target)).willReturn(Optional.ofNullable(targetInfo));
		given(fileOpsUsage.fileInfoUsage(any(), any())).willReturn(expected);

		// when:
		FeeData actual = subject.usageGiven(answerOnlyQuery, view);

		// then:
		assertSame(expected, actual);
		// and:
		verify(fileOpsUsage).fileInfoUsage(argThat(answerOnlyQuery::equals), captor.capture());
		// and:
		var ctxUsed = captor.getValue();
		assertEquals(expiry, ctxUsed.currentExpiry());
		assertEquals(memo, ctxUsed.currentMemo());
		assertEquals(wacl.getKeyList(), ctxUsed.currentWacl());
		assertEquals(size, ctxUsed.currentSize());
	}

	@Test
	void recognizesApplicableQuery() {
		// given:
		Query fileInfoQuery = fileInfoQuery(target, COST_ANSWER);
		Query nonFileInfoQuery = nonFileInfoQuery();

		// expect:
		assertTrue(subject.applicableTo(fileInfoQuery));
		assertFalse(subject.applicableTo(nonFileInfoQuery));
	}

	private Query fileInfoQuery(FileID id, ResponseType type) {
		FileGetInfoQuery.Builder op = FileGetInfoQuery.newBuilder()
				.setFileID(id)
				.setHeader(QueryHeader.newBuilder().setResponseType(type));
		return Query.newBuilder()
				.setFileGetInfo(op)
				.build();
	}

	private Query nonFileInfoQuery() {
		return Query.newBuilder().build();
	}
}
