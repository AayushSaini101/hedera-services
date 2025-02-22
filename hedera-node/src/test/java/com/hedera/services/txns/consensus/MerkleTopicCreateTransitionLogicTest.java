package com.hedera.services.txns.consensus;

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


import com.hedera.services.context.TransactionContext;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.factories.txns.SignedTxnFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.hedera.test.factories.scenarios.TxnHandlingScenario.MISC_ACCOUNT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.MISC_ACCOUNT_KT;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_ACCOUNT_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

class MerkleTopicCreateTransitionLogicTest {
	private static final long VALID_AUTORENEW_PERIOD_SECONDS = 30 * 86400L;
	private static final long INVALID_AUTORENEW_PERIOD_SECONDS = -1L;
	private static final String TOO_LONG_MEMO = "too-long";
	private static final String VALID_MEMO = "memo";
	private static final AccountID NEW_TOPIC_ID = asAccount("7.6.54321");

	// key to be used as a valid admin or submit key.
	final private Key key = SignedTxnFactory.DEFAULT_PAYER_KT.asKey();

	private Instant expirationTimestamp;
	private Instant consensusTimestamp;
	private TransactionBody transactionBody;
	private TransactionContext transactionContext;
	private PlatformTxnAccessor accessor;
	private OptionValidator validator;
	private TopicCreateTransitionLogic subject;
	private FCMap<MerkleEntityId, MerkleAccount> accounts = new FCMap<>();
	private FCMap<MerkleEntityId, MerkleTopic> topics = new FCMap<>();
	private EntityIdSource entityIdSource;
	private HederaLedger ledger;
	final private AccountID payer = AccountID.newBuilder().setAccountNum(2_345L).build();

	@BeforeEach
	private void setup() {
		consensusTimestamp = Instant.ofEpochSecond(1546304463);

		transactionContext = mock(TransactionContext.class);
		given(transactionContext.consensusTime()).willReturn(consensusTimestamp);
		accessor = mock(PlatformTxnAccessor.class);
		validator = mock(OptionValidator.class);
		given(validator.isValidAutoRenewPeriod(Duration.newBuilder().setSeconds(VALID_AUTORENEW_PERIOD_SECONDS).build()))
				.willReturn(true);
		given(validator.isValidAutoRenewPeriod(
				Duration.newBuilder().setSeconds(INVALID_AUTORENEW_PERIOD_SECONDS).build()))
				.willReturn(false);
		given(validator.memoCheck(VALID_MEMO)).willReturn(OK);
		given(validator.memoCheck(TOO_LONG_MEMO)).willReturn(MEMO_TOO_LONG);
		entityIdSource = mock(EntityIdSource.class);
		given(entityIdSource.newAccountId(any())).willReturn(NEW_TOPIC_ID);
		accounts.clear();
		topics.clear();

		ledger = mock(HederaLedger.class);

		subject = new TopicCreateTransitionLogic(
				() -> accounts, () -> topics, entityIdSource, validator, transactionContext, ledger);
	}

	@Test
	void hasCorrectApplicability() {
		// given:
		givenValidTransactionWithAllOptions();

		// expect:
		assertTrue(subject.applicability().test(transactionBody));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	void syntaxCheckWithAdminKey() {
		// given:
		givenValidTransactionWithAllOptions();
		given(validator.hasGoodEncoding(key)).willReturn(true);

		// expect:
		assertEquals(OK, subject.semanticCheck().apply(transactionBody));
	}

	@Test
	void syntaxCheckWithInvalidAdminKey() {
		// given:
		givenValidTransactionWithAllOptions();
		given(validator.hasGoodEncoding(key)).willReturn(false);

		// expect:
		assertEquals(BAD_ENCODING, subject.semanticCheck().apply(transactionBody));
	}

	@Test
	void followsHappyPath() throws Throwable {
		// given:
		expirationTimestamp = consensusTimestamp.plusSeconds(VALID_AUTORENEW_PERIOD_SECONDS);
		givenValidTransactionWithAllOptions();

		// when:
		subject.doStateTransition();

		// then:
		var topic = topics.get(MerkleEntityId.fromAccountId(NEW_TOPIC_ID));
		assertNotNull(topic);
		assertEquals(VALID_MEMO, topic.getMemo());
		assertArrayEquals(JKey.mapKey(key).serialize(), topic.getAdminKey().serialize());
		assertArrayEquals(JKey.mapKey(key).serialize(), topic.getSubmitKey().serialize());
		assertEquals(VALID_AUTORENEW_PERIOD_SECONDS, topic.getAutoRenewDurationSeconds());
		assertEquals(EntityId.fromGrpcAccountId(MISC_ACCOUNT), topic.getAutoRenewAccountId());
		assertEquals(expirationTimestamp.getEpochSecond(), topic.getExpirationTimestamp().getSeconds());
		verify(transactionContext).setStatus(SUCCESS);
	}

	@Test
	void memoTooLong() throws Throwable {
		// given:
		givenTransactionWithTooLongMemo();

		// when:
		subject.doStateTransition();

		// then:
		assertTrue(topics.isEmpty());
		verify(transactionContext).setStatus(MEMO_TOO_LONG);
	}

	@Test
	void badSubmitKey() throws Throwable {
		// given:
		givenTransactionWithInvalidSubmitKey();

		// when:
		subject.doStateTransition();

		// then:
		assertTrue(topics.isEmpty());
		verify(transactionContext).setStatus(BAD_ENCODING);
	}

	@Test
	void missingAutoRenewPeriod() throws Throwable {
		// given:
		givenTransactionWithMissingAutoRenewPeriod();

		// when:
		subject.doStateTransition();

		// then:
		assertTrue(topics.isEmpty());
		verify(transactionContext).setStatus(INVALID_RENEWAL_PERIOD);
	}

	@Test
	void badAutoRenewPeriod() throws Throwable {
		// given:
		givenTransactionWithInvalidAutoRenewPeriod();

		// when:
		subject.doStateTransition();

		// then:
		assertTrue(topics.isEmpty());
		verify(transactionContext).setStatus(AUTORENEW_DURATION_NOT_IN_RANGE);
	}

	@Test
	void invalidAutoRenewAccountId() throws Throwable {
		// given:
		givenTransactionWithInvalidAutoRenewAccountId();

		// when:
		subject.doStateTransition();

		// then:
		assertTrue(topics.isEmpty());
		verify(transactionContext).setStatus(INVALID_AUTORENEW_ACCOUNT);
	}

	@Test
	void detachedAutoRenewAccountId() throws Throwable {
		// given:
		givenTransactionWithDetachedAutoRenewAccountId();

		// when:
		subject.doStateTransition();

		// then:
		assertTrue(topics.isEmpty());
		verify(transactionContext).setStatus(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
	}

	@Test
	void autoRenewAccountNotAllowed() throws Throwable {
		// given:
		givenTransactionWithAutoRenewAccountWithoutAdminKey();

		// when:
		subject.doStateTransition();

		// then:
		assertTrue(topics.isEmpty());
		verify(transactionContext).setStatus(AUTORENEW_ACCOUNT_NOT_ALLOWED);
	}

	private void givenTransaction(ConsensusCreateTopicTransactionBody.Builder body) {
		transactionBody = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setConsensusCreateTopic(body.build())
				.build();
		given(accessor.getTxn()).willReturn(transactionBody);
		given(transactionContext.accessor()).willReturn(accessor);
	}

	private ConsensusCreateTopicTransactionBody.Builder getBasicValidTransactionBodyBuilder() {
		return ConsensusCreateTopicTransactionBody.newBuilder()
				.setAutoRenewPeriod(Duration.newBuilder()
						.setSeconds(VALID_AUTORENEW_PERIOD_SECONDS).build());
	}

	private void givenValidTransactionWithAllOptions() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setMemo(VALID_MEMO)
						.setAdminKey(key)
						.setSubmitKey(key)
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
		given(validator.hasGoodEncoding(key)).willReturn(true);
		given(validator.queryableAccountStatus(MISC_ACCOUNT, accounts)).willReturn(OK);
	}

	private void givenTransactionWithTooLongMemo() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setMemo(TOO_LONG_MEMO)
		);
	}

	private void givenTransactionWithInvalidSubmitKey() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setSubmitKey(MISC_ACCOUNT_KT.asKey())
		);
		given(validator.hasGoodEncoding(MISC_ACCOUNT_KT.asKey())).willReturn(false);
	}

	private void givenTransactionWithInvalidAutoRenewPeriod() {
		givenTransaction(
				ConsensusCreateTopicTransactionBody.newBuilder()
						.setAutoRenewPeriod(Duration.newBuilder()
								.setSeconds(INVALID_AUTORENEW_PERIOD_SECONDS).build())
		);
	}

	private void givenTransactionWithMissingAutoRenewPeriod() {
		givenTransaction(
				ConsensusCreateTopicTransactionBody.newBuilder()
		);
	}

	private void givenTransactionWithInvalidAutoRenewAccountId() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
		given(validator.queryableAccountStatus(MISC_ACCOUNT, accounts)).willReturn(INVALID_ACCOUNT_ID);
	}

	private void givenTransactionWithDetachedAutoRenewAccountId() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
		given(validator.queryableAccountStatus(MISC_ACCOUNT, accounts)).willReturn(OK);
		given(ledger.isDetached(MISC_ACCOUNT)).willReturn(true);
	}

	private void givenTransactionWithAutoRenewAccountWithoutAdminKey() {
		givenTransaction(
				getBasicValidTransactionBodyBuilder()
						.setAutoRenewAccount(MISC_ACCOUNT)
		);
		given(validator.queryableAccountStatus(MISC_ACCOUNT, accounts)).willReturn(OK);
	}

	private TransactionID ourTxnId() {
		return TransactionID.newBuilder()
				.setAccountID(payer)
				.setTransactionValidStart(
						Timestamp.newBuilder().setSeconds(consensusTimestamp.getEpochSecond()))
				.build();
	}
}
