package com.hedera.services.ledger;

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

import com.hedera.services.exceptions.DeletedAccountException;
import com.hedera.services.exceptions.InsufficientFundsException;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.submerkle.FcTokenAssociation;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.test.utils.IdUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.hedera.services.exceptions.InsufficientFundsException.messageFor;
import static com.hedera.services.ledger.properties.AccountProperty.ALREADY_USED_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.services.ledger.properties.AccountProperty.AUTO_RENEW_PERIOD;
import static com.hedera.services.ledger.properties.AccountProperty.BALANCE;
import static com.hedera.services.ledger.properties.AccountProperty.EXPIRY;
import static com.hedera.services.ledger.properties.AccountProperty.IS_DELETED;
import static com.hedera.services.ledger.properties.AccountProperty.IS_RECEIVER_SIG_REQUIRED;
import static com.hedera.services.ledger.properties.AccountProperty.IS_SMART_CONTRACT;
import static com.hedera.services.ledger.properties.AccountProperty.MAX_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.services.ledger.properties.AccountProperty.PROXY;
import static com.hedera.test.utils.IdUtils.asAccount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.times;
import static org.mockito.BDDMockito.verify;

class HederaLedgerTest extends BaseHederaLedgerTestHelper {
	@BeforeEach
	private void setup() {
		commonSetup();
		setupWithMockLedger();
	}

	@Test
	void delegatesDestroy() {
		subject.destroy(genesis);

		verify(accountsLedger).destroy(genesis);
	}

	@Test
	void indicatesNoChangeSetIfNotInTx() {
		final var summary = subject.currentChangeSet();

		verify(accountsLedger, never()).changeSetSoFar();
		assertEquals(HederaLedger.NO_ACTIVE_TXN_CHANGE_SET, summary);
	}

	@Test
	void delegatesChangeSetIfInTxn() {
		final var zeroingGenesis = "{0.0.2: [BALANCE -> 0]}";
		final var creatingTreasury = "{0.0.2 <-> 0.0.1001: [TOKEN_BALANCE -> 1_000_000]}";
		final var changingOwner = "{NftId{shard=0, realm=0, num=10000, serialNo=1234}: " +
				"[OWNER -> EntityId{shard=3, realm=4, num=5}]}";
		given(accountsLedger.isInTransaction()).willReturn(true);
		given(accountsLedger.changeSetSoFar()).willReturn(zeroingGenesis);
		given(tokenRelsLedger.changeSetSoFar()).willReturn(creatingTreasury);
		given(nftsLedger.changeSetSoFar()).willReturn(changingOwner);

		final var summary = subject.currentChangeSet();

		verify(accountsLedger).changeSetSoFar();
		final var desired = "--- ACCOUNTS ---\n" +
				"{0.0.2: [BALANCE -> 0]}\n" +
				"--- TOKEN RELATIONSHIPS ---\n" +
				"{0.0.2 <-> 0.0.1001: [TOKEN_BALANCE -> 1_000_000]}\n" +
				"--- NFTS ---\n" +
				"{NftId{shard=0, realm=0, num=10000, serialNo=1234}: [OWNER -> EntityId{shard=3, realm=4, num=5}]}";
		assertEquals(desired, summary);
	}

	@Test
	void delegatesGet() {
		final var fakeGenesis = new MerkleAccount();
		given(accountsLedger.getFinalized(genesis)).willReturn(fakeGenesis);

		assertSame(fakeGenesis, subject.get(genesis));
	}

	@Test
	void delegatesExists() {
		final var missing = asAccount("55.66.77");

		final var hasMissing = subject.exists(missing);
		final var hasGenesis = subject.exists(genesis);

		verify(accountsLedger, times(2)).exists(any());
		assertTrue(hasGenesis);
		assertFalse(hasMissing);
	}

	@Test
	void setsCreatorOnHistorian() {
		verify(historian).setCreator(creator);
	}

	@Test
	void delegatesToCorrectContractProperty() {
		subject.isSmartContract(genesis);

		verify(accountsLedger).get(genesis, IS_SMART_CONTRACT);
	}

	@Test
	void delegatesToCorrectDeletionProperty() {
		subject.isDeleted(genesis);

		verify(accountsLedger).get(genesis, IS_DELETED);
	}

	@Test
	void delegatesToCorrectSigReqProperty() {
		subject.isReceiverSigRequired(genesis);

		verify(accountsLedger).get(genesis, IS_RECEIVER_SIG_REQUIRED);
	}

	@Test
	void recognizesDetached() {
		validator = mock(OptionValidator.class);
		given(validator.isAfterConsensusSecond(anyLong())).willReturn(false);
		given(accountsLedger.get(genesis, BALANCE)).willReturn(0L);
		subject = new HederaLedger(tokenStore, ids, creator, validator, historian, dynamicProps, accountsLedger);

		assertTrue(subject.isDetached(genesis));
	}

	@Test
	void recognizesCannotBeDetachedIfContract() {
		validator = mock(OptionValidator.class);
		given(validator.isAfterConsensusSecond(anyLong())).willReturn(false);
		given(accountsLedger.get(genesis, BALANCE)).willReturn(0L);
		given(accountsLedger.get(genesis, IS_SMART_CONTRACT)).willReturn(true);
		subject = new HederaLedger(tokenStore, ids, creator, validator, historian, dynamicProps, accountsLedger);

		assertFalse(subject.isDetached(genesis));
	}

	@Test
	void recognizesCannotBeDetachedIfAutoRenewDisabled() {
		validator = mock(OptionValidator.class);
		given(validator.isAfterConsensusSecond(anyLong())).willReturn(false);
		given(accountsLedger.get(genesis, BALANCE)).willReturn(0L);
		subject = new HederaLedger(tokenStore, ids, creator, validator, historian, dynamicProps, accountsLedger);
		dynamicProps.disableAutoRenew();

		assertFalse(subject.isDetached(genesis));
	}

	@Test
	void delegatesToCorrectExpiryProperty() {
		subject.expiry(genesis);

		verify(accountsLedger).get(genesis, EXPIRY);
	}

	@Test
	void delegatesToCorrectAutoRenewProperty() {
		subject.autoRenewPeriod(genesis);

		verify(accountsLedger).get(genesis, AUTO_RENEW_PERIOD);
	}

	@Test
	void delegatesToCorrectProxyProperty() {
		subject.proxy(genesis);

		verify(accountsLedger).get(genesis, PROXY);
	}

	@Test
	void delegatesToCorrectMaxAutomaticAssociationsProperty() {
		subject.maxAutomaticAssociations(genesis);
		verify(accountsLedger).get(genesis, MAX_AUTOMATIC_ASSOCIATIONS);

		subject.setMaxAutomaticAssociations(genesis, 10);
		verify(accountsLedger).set(genesis, MAX_AUTOMATIC_ASSOCIATIONS, 10);
	}

	@Test
	void delegatesToCorrectAlreadyUsedAutomaticAssociationProperty() {
		subject.alreadyUsedAutomaticAssociations(genesis);
		verify(accountsLedger).get(genesis, ALREADY_USED_AUTOMATIC_ASSOCIATIONS);

		subject.setAlreadyUsedAutomaticAssociations(genesis, 7);
		verify(accountsLedger).set(genesis, ALREADY_USED_AUTOMATIC_ASSOCIATIONS, 7);
	}

	@Test
	void throwsOnUnderfundedCreate() {
		assertThrows(InsufficientFundsException.class, () ->
				subject.create(rand, RAND_BALANCE + 1, noopCustomizer));
	}

	@Test
	void performsFundedCreate() {
		final var customizer = mock(HederaAccountCustomizer.class);
		given(accountsLedger.existsPending(IdUtils.asAccount(String.format("0.0.%d", NEXT_ID)))).willReturn(true);

		final var created = subject.create(rand, 1_000L, customizer);

		assertEquals(NEXT_ID, created.getAccountNum());
		verify(accountsLedger).set(rand, BALANCE, RAND_BALANCE - 1_000L);
		verify(accountsLedger).create(created);
		verify(accountsLedger).set(created, BALANCE, 1_000L);
		verify(customizer).customize(created, accountsLedger);
	}

	@Test
	void performsUnconditionalSpawn() {
		final var customizer = mock(HederaAccountCustomizer.class);
		final var contract = asAccount("1.2.3");
		final var balance = 1_234L;
		given(accountsLedger.existsPending(contract)).willReturn(true);

		subject.spawn(contract, balance, customizer);

		verify(accountsLedger).create(contract);
		verify(accountsLedger).set(contract, BALANCE, balance);
		verify(customizer).customize(contract, accountsLedger);
	}

	@Test
	void deletesGivenAccount() {
		subject.delete(rand, misc);

		verify(accountsLedger).set(rand, BALANCE, 0L);
		verify(accountsLedger).set(misc, BALANCE, MISC_BALANCE + RAND_BALANCE);
		verify(accountsLedger).set(rand, IS_DELETED, true);
	}

	@Test
	void throwsOnCustomizingDeletedAccount() {
		assertThrows(DeletedAccountException.class, () -> subject.customize(deleted, noopCustomizer));
	}

	@Test
	void throwsOnDeleteCustomizingUndeletedAccount() {
		assertThrows(DeletedAccountException.class, () -> subject.customizeDeleted(rand, noopCustomizer));
	}

	@Test
	void customizesGivenAccount() {
		final var customizer = mock(HederaAccountCustomizer.class);

		subject.customize(rand, customizer);

		verify(customizer).customize(rand, accountsLedger);
	}

	@Test
	void customizesDeletedAccount() {
		final var customizer = mock(HederaAccountCustomizer.class);

		subject.customizeDeleted(deleted, customizer);

		verify(customizer).customize(deleted, accountsLedger);
	}

	@Test
	void makesPossibleAdjustment() {
		final var amount = -1 * GENESIS_BALANCE / 2;

		subject.adjustBalance(genesis, amount);

		verify(accountsLedger).set(genesis, BALANCE, GENESIS_BALANCE + amount);
	}

	@Test
	void throwsOnNegativeBalance() {
		final var overdraftAdjustment = -1 * GENESIS_BALANCE - 1;

		final var e = assertThrows(InsufficientFundsException.class,
				() -> subject.adjustBalance(genesis, overdraftAdjustment));

		assertEquals(messageFor(genesis, overdraftAdjustment), e.getMessage());
		verify(accountsLedger, never()).set(any(), any(), any());
	}

	@Test
	void forwardsGetBalanceCorrectly() {
		final var balance = subject.getBalance(genesis);

		assertEquals(GENESIS_BALANCE, balance);
	}

	@Test
	void forwardsTransactionalSemantics() {
		subject.setTokenRelsLedger(null);
		final var inOrder = inOrder(accountsLedger);

		subject.begin();
		subject.commit();
		subject.begin();
		subject.rollback();

		inOrder.verify(accountsLedger).begin();
		inOrder.verify(accountsLedger).commit();
		inOrder.verify(accountsLedger).begin();
		inOrder.verify(accountsLedger).rollback();
	}

	@Test
	void persistsNewTokenAssociationsAsExpected() {
		final var tokenNum = 3;
		final var accountNum = 4;
		final var tokenAssociation = new FcTokenAssociation(tokenNum, accountNum);
		subject.addNewAssociationToList(tokenAssociation);

		assertEquals(tokenAssociation, subject.getNewTokenAssociations().get(0));
	}
}
