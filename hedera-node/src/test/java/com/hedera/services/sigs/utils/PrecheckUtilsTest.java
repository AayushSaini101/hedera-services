package com.hedera.services.sigs.utils;

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

import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.factories.txns.SignedTxnFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static com.hedera.services.sigs.utils.PrecheckUtils.queryPaymentTestFor;
import static com.hedera.test.factories.txns.CryptoTransferFactory.newSignedCryptoTransfer;
import static com.hedera.test.factories.txns.CryptoUpdateFactory.newSignedCryptoUpdate;
import static com.hedera.test.factories.txns.PlatformTxnFactory.from;
import static com.hedera.test.factories.txns.TinyBarsFromTo.tinyBarsFromTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PrecheckUtilsTest {
	private static final String nodeId = SignedTxnFactory.DEFAULT_NODE_ID;
	private static final AccountID node = SignedTxnFactory.DEFAULT_NODE;
	private static final Predicate<TransactionBody> subject = queryPaymentTestFor(node);

	@Test
	void queryPaymentsMustBeCryptoTransfers() throws Throwable {
		final var txn = new PlatformTxnAccessor(from(
				newSignedCryptoUpdate("0.0.2").get()
		)).getTxn();

		assertFalse(subject.test(txn));
	}

	@Test
	void transferWithoutTargetNodeIsNotQueryPayment() throws Throwable {
		final var txn = new PlatformTxnAccessor(from(
				newSignedCryptoTransfer().transfers(
						tinyBarsFromTo("0.0.1024", "0.0.2048", 1_000L)
				).get()
		)).getTxn();

		assertFalse(subject.test(txn));
	}

	@Test
	void queryPaymentTransfersToTargetNode() throws Throwable {
		final var txn = new PlatformTxnAccessor(from(
				newSignedCryptoTransfer().transfers(
						tinyBarsFromTo(nodeId, "0.0.2048", 1_000L)
				).get()
		)).getTxn();

		assertFalse(subject.test(txn));
	}
}
