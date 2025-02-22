package com.hedera.services.txns.crypto;

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
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.exceptions.InsufficientFundsException;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.factories.txns.SignedTxnFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.EnumMap;

import static com.hedera.services.ledger.properties.AccountProperty.AUTO_RENEW_PERIOD;
import static com.hedera.services.ledger.properties.AccountProperty.EXPIRY;
import static com.hedera.services.ledger.properties.AccountProperty.IS_RECEIVER_SIG_REQUIRED;
import static com.hedera.services.ledger.properties.AccountProperty.KEY;
import static com.hedera.services.ledger.properties.AccountProperty.MAX_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.services.ledger.properties.AccountProperty.MEMO;
import static com.hedera.services.ledger.properties.AccountProperty.PROXY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_INITIAL_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RECEIVE_RECORD_THRESHOLD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SEND_RECORD_THRESHOLD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.anyLong;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.longThat;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

class CryptoCreateTransitionLogicTest {
	final private Key key = SignedTxnFactory.DEFAULT_PAYER_KT.asKey();
	final private long customAutoRenewPeriod = 100_001L;
	final private long customSendThreshold = 49_000L;
	final private long customReceiveThreshold = 51_001L;
	final private Long balance = 1_234L;
	final private String memo = "The particular is pounded til it is man";
	final private int maxAutoAssociations = 1234;
	final private int maxTokenAssociations = 12345;
	final private AccountID proxy = AccountID.newBuilder().setAccountNum(4_321L).build();
	final private AccountID payer = AccountID.newBuilder().setAccountNum(1_234L).build();
	final private AccountID created = AccountID.newBuilder().setAccountNum(9_999L).build();

	private long expiry;
	private Instant consensusTime;
	private HederaLedger ledger;
	private OptionValidator validator;
	private TransactionBody cryptoCreateTxn;
	private TransactionContext txnCtx;
	private PlatformTxnAccessor accessor;
	private CryptoCreateTransitionLogic subject;
	private GlobalDynamicProperties dynamicProperties;

	@BeforeEach
	private void setup() {
		consensusTime = Instant.now();

		txnCtx = mock(TransactionContext.class);
		given(txnCtx.consensusTime()).willReturn(consensusTime);
		ledger = mock(HederaLedger.class);
		accessor = mock(PlatformTxnAccessor.class);
		validator = mock(OptionValidator.class);
		dynamicProperties = mock(GlobalDynamicProperties.class);
		given(dynamicProperties.maxTokensPerAccount()).willReturn(maxTokenAssociations);
		withRubberstampingValidator();

		subject = new CryptoCreateTransitionLogic(ledger, validator, txnCtx, dynamicProperties);
	}

	@Test
	void hasCorrectApplicability() {
		givenValidTxnCtx();

		// expect:
		assertTrue(subject.applicability().test(cryptoCreateTxn));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	void returnsMemoTooLongWhenValidatorSays() {
		givenValidTxnCtx();
		given(validator.memoCheck(memo)).willReturn(MEMO_TOO_LONG);

		// expect:
		assertEquals(MEMO_TOO_LONG, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void returnsKeyRequiredOnEmptyKey() {
		givenValidTxnCtx(Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build());

		// expect:
		assertEquals(KEY_REQUIRED, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void requiresKey() {
		givenMissingKey();

		// expect:
		assertEquals(KEY_REQUIRED, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void rejectsMissingAutoRenewPeriod() {
		givenMissingAutoRenewPeriod();

		// expect:
		assertEquals(INVALID_RENEWAL_PERIOD, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void rejectsNegativeBalance() {
		givenAbsurdInitialBalance();

		// expect:
		assertEquals(INVALID_INITIAL_BALANCE, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void rejectsNegativeSendThreshold() {
		givenAbsurdSendThreshold();

		// expect:
		assertEquals(INVALID_SEND_RECORD_THRESHOLD, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void rejectsNegativeReceiveThreshold() {
		givenAbsurdReceiveThreshold();

		// expect:
		assertEquals(INVALID_RECEIVE_RECORD_THRESHOLD, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void rejectsKeyWithBadEncoding() {
		givenValidTxnCtx();
		given(validator.hasGoodEncoding(any())).willReturn(false);

		// expect:
		assertEquals(BAD_ENCODING, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void rejectsInvalidAutoRenewPeriod() {
		givenValidTxnCtx();
		given(validator.isValidAutoRenewPeriod(any())).willReturn(false);

		// expect:
		assertEquals(AUTORENEW_DURATION_NOT_IN_RANGE, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void acceptsValidTxn() {
		givenValidTxnCtx();

		// expect:
		assertEquals(OK, subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void rejectsInvalidMaxAutomaticAssociations() {
		givenInvalidMaxAutoAssociations();;

		assertEquals(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT,
				subject.semanticCheck().apply(cryptoCreateTxn));
	}

	@Test
	void followsHappyPathWithOverrides() throws Throwable {
		// setup:
		ArgumentCaptor<HederaAccountCustomizer> captor = ArgumentCaptor.forClass(HederaAccountCustomizer.class);
		expiry = consensusTime.getEpochSecond() + customAutoRenewPeriod;

		givenValidTxnCtx();
		// and:
		given(ledger.create(any(), anyLong(), any())).willReturn(created);

		// when:
		subject.doStateTransition();

		// then:
		verify(ledger).create(argThat(payer::equals), longThat(balance::equals), captor.capture());
		verify(txnCtx).setCreated(created);
		verify(txnCtx).setStatus(SUCCESS);
		// and:
		EnumMap<AccountProperty, Object> changes = captor.getValue().getChanges();
		assertEquals(7, changes.size());
		assertEquals(customAutoRenewPeriod, (long)changes.get(AUTO_RENEW_PERIOD));
		assertEquals(expiry, (long)changes.get(EXPIRY));
		assertEquals(key, JKey.mapJKey((JKey)changes.get(KEY)));
		assertEquals(true, changes.get(IS_RECEIVER_SIG_REQUIRED));
		assertEquals(EntityId.fromGrpcAccountId(proxy), changes.get(PROXY));
		assertEquals(memo, changes.get(MEMO));
		assertEquals(maxAutoAssociations, changes.get(MAX_AUTOMATIC_ASSOCIATIONS));
	}

	@Test
	void translatesInsufficientPayerBalance() {
		givenValidTxnCtx();
		given(ledger.create(any(), anyLong(), any())).willThrow(InsufficientFundsException.class);

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(INSUFFICIENT_PAYER_BALANCE);
	}

	@Test
	void translatesUnknownException() {
		givenValidTxnCtx();
		cryptoCreateTxn = cryptoCreateTxn.toBuilder()
				.setCryptoCreateAccount(cryptoCreateTxn.getCryptoCreateAccount().toBuilder().setKey(unmappableKey()))
				.build();
		given(accessor.getTxn()).willReturn(cryptoCreateTxn);
		given(txnCtx.accessor()).willReturn(accessor);

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(FAIL_INVALID);
	}

	private Key unmappableKey() {
		return Key.getDefaultInstance();
	}

	private void givenMissingKey() {
		cryptoCreateTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoCreateAccount(
						CryptoCreateTransactionBody.newBuilder()
								.setInitialBalance(balance)
								.build()
				).build();
	}

	private void givenMissingAutoRenewPeriod() {
		cryptoCreateTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoCreateAccount(
						CryptoCreateTransactionBody.newBuilder()
								.setKey(key)
								.setInitialBalance(balance)
								.build()
				).build();
	}

	private void givenAbsurdSendThreshold() {
		cryptoCreateTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoCreateAccount(
						CryptoCreateTransactionBody.newBuilder()
								.setAutoRenewPeriod(Duration.newBuilder().setSeconds(1L))
								.setKey(key)
								.setSendRecordThreshold(-1L)
								.build()
				).build();
	}

	private void givenAbsurdReceiveThreshold() {
		cryptoCreateTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoCreateAccount(
						CryptoCreateTransactionBody.newBuilder()
								.setAutoRenewPeriod(Duration.newBuilder().setSeconds(1L))
								.setKey(key)
								.setReceiveRecordThreshold(-1L)
								.build()
				).build();
	}

	private void givenAbsurdInitialBalance() {
		cryptoCreateTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoCreateAccount(
						CryptoCreateTransactionBody.newBuilder()
								.setAutoRenewPeriod(Duration.newBuilder().setSeconds(1L))
								.setKey(key)
								.setInitialBalance(-1L)
								.build()
				).build();
	}

	private void givenInvalidMaxAutoAssociations() {
		cryptoCreateTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoCreateAccount(
						CryptoCreateTransactionBody.newBuilder()
								.setMemo(memo)
								.setInitialBalance(balance)
								.setProxyAccountID(proxy)
								.setReceiverSigRequired(true)
								.setAutoRenewPeriod(Duration.newBuilder().setSeconds(customAutoRenewPeriod))
								.setReceiveRecordThreshold(customReceiveThreshold)
								.setSendRecordThreshold(customSendThreshold)
								.setKey(key)
								.setMaxAutomaticTokenAssociations(maxTokenAssociations+1)
								.build()
				).build();
	}

	private void givenValidTxnCtx() {
		givenValidTxnCtx(key);
	}

	private void givenValidTxnCtx(Key toUse) {
		cryptoCreateTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoCreateAccount(
						CryptoCreateTransactionBody.newBuilder()
								.setMemo(memo)
								.setInitialBalance(balance)
								.setProxyAccountID(proxy)
								.setReceiverSigRequired(true)
								.setAutoRenewPeriod(Duration.newBuilder().setSeconds(customAutoRenewPeriod))
								.setReceiveRecordThreshold(customReceiveThreshold)
								.setSendRecordThreshold(customSendThreshold)
								.setKey(toUse)
								.setMaxAutomaticTokenAssociations(maxAutoAssociations)
								.build()
				).build();
		given(accessor.getTxn()).willReturn(cryptoCreateTxn);
		given(txnCtx.accessor()).willReturn(accessor);
	}

	private TransactionID ourTxnId() {
		return TransactionID.newBuilder()
				.setAccountID(payer)
				.setTransactionValidStart(
						Timestamp.newBuilder().setSeconds(consensusTime.getEpochSecond()))
				.build();
	}

	private void withRubberstampingValidator() {
		given(validator.isValidAutoRenewPeriod(any())).willReturn(true);
		given(validator.hasGoodEncoding(any())).willReturn(true);
		given(validator.memoCheck(any())).willReturn(OK);
	}
}
