package com.hedera.services.fees.calculation.token.txns;

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
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.token.TokenUnfreezeUsage;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.fee.SigValueObj;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;

class TokenUnfreezeResourceUsageTest {
	private TransactionBody nonTokenUnfreezeTxn;
	private TransactionBody tokenUnfreezeTxn;

	int numSigs = 10, sigsSize = 100, numPayerKeys = 3;
	SigValueObj obj = new SigValueObj(numSigs, numPayerKeys, sigsSize);
	SigUsage sigUsage = new SigUsage(numSigs, sigsSize, numPayerKeys);

	BiFunction<TransactionBody, SigUsage, TokenUnfreezeUsage> factory;
	StateView view;
	TokenUnfreezeUsage usage;
	TokenUnfreezeResourceUsage subject;
	FeeData expected;

	@BeforeEach
	private void setup() throws Throwable {
		expected = mock(FeeData.class);
		tokenUnfreezeTxn = mock(TransactionBody.class);
		given(tokenUnfreezeTxn.hasTokenUnfreeze()).willReturn(true);

		nonTokenUnfreezeTxn = mock(TransactionBody.class);
		given(nonTokenUnfreezeTxn.hasTokenUnfreeze()).willReturn(false);

		usage = mock(TokenUnfreezeUsage.class);
		given(usage.get()).willReturn(expected);

		factory = (BiFunction<TransactionBody, SigUsage, TokenUnfreezeUsage>)mock(BiFunction.class);
		given(factory.apply(tokenUnfreezeTxn, sigUsage)).willReturn(usage);

		TokenUnfreezeResourceUsage.factory = factory;

		subject = new TokenUnfreezeResourceUsage();
	}

	@Test
	void recognizesApplicability() {
		// expect:
		assertTrue(subject.applicableTo(tokenUnfreezeTxn));
		assertFalse(subject.applicableTo(nonTokenUnfreezeTxn));
	}

	@Test
	void delegatesToCorrectEstimate() throws Exception {
		// expect:
		assertEquals(
				expected,
				subject.usageGiven(tokenUnfreezeTxn, obj, view));
	}
}
