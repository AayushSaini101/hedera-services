package com.hedera.services.state.logic;

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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.state.expiry.EntityAutoRenewal;
import com.hedera.services.state.expiry.ExpiryManager;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.services.utils.TxnAccessor;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import com.swirlds.common.SwirldTransaction;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith({ MockitoExtension.class, LogCaptureExtension.class })
class StandardProcessLogicTest {
	private final long member = 1L;
	private final Instant consensusNow = Instant.ofEpochSecond(1_234_567L, 890);
	private final Instant triggeredConsensusNow = consensusNow.minusNanos(1);

	@Mock
	private ExpiryManager expiries;
	@Mock
	private InvariantChecks invariantChecks;
	@Mock
	private ExpandHandleSpan expandHandleSpan;
	@Mock
	private EntityAutoRenewal autoRenewal;
	@Mock
	private ServicesTxnManager txnManager;
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private PlatformTxnAccessor accessor;
	@Mock
	private TxnAccessor triggeredAccessor;
	@Mock
	private SwirldTransaction swirldTransaction;

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private StandardProcessLogic subject;

	@BeforeEach
	void setUp() {
		subject = new StandardProcessLogic(expiries, invariantChecks, expandHandleSpan, autoRenewal, txnManager,
				txnCtx);
	}

	@Test
	void happyPathFlowsForNonTriggered() throws InvalidProtocolBufferException {
		given(expandHandleSpan.accessorFor(swirldTransaction)).willReturn(accessor);
		given(invariantChecks.holdFor(accessor, consensusNow, member)).willReturn(true);

		// when:
		subject.incorporateConsensusTxn(swirldTransaction, consensusNow, member);

		// then:
		verify(expiries).purge(consensusNow.getEpochSecond());
		verify(txnManager).process(accessor, consensusNow, member);
		verify(autoRenewal).execute(consensusNow);
	}

	@Test
	void abortsOnFailedInvariantCheck() throws InvalidProtocolBufferException {
		given(expandHandleSpan.accessorFor(swirldTransaction)).willReturn(accessor);

		// when:
		subject.incorporateConsensusTxn(swirldTransaction, consensusNow, member);

		// then:
		verifyNoInteractions(expiries, txnManager, autoRenewal);
	}

	@Test
	void happyPathFlowsForTriggered() throws InvalidProtocolBufferException {
		given(accessor.canTriggerTxn()).willReturn(true);
		given(expandHandleSpan.accessorFor(swirldTransaction)).willReturn(accessor);
		given(invariantChecks.holdFor(accessor, triggeredConsensusNow, member)).willReturn(true);
		given(txnCtx.triggeredTxn()).willReturn(triggeredAccessor);

		// when:
		subject.incorporateConsensusTxn(swirldTransaction, consensusNow, member);

		// then:
		verify(expiries).purge(consensusNow.getEpochSecond());
		verify(txnManager).process(accessor, triggeredConsensusNow, member);
		verify(txnManager).process(triggeredAccessor, consensusNow, member);
		verify(autoRenewal).execute(consensusNow);
	}

	@Test
	void warnsOnNonGrpc() throws InvalidProtocolBufferException {
		given(expandHandleSpan.accessorFor(swirldTransaction)).willThrow(InvalidProtocolBufferException.class);

		// when:
		subject.incorporateConsensusTxn(swirldTransaction, consensusNow, member);

		// then:
		assertThat(logCaptor.warnLogs(), contains(Matchers.startsWith("Consensus platform txn was not gRPC!")));
	}
}
