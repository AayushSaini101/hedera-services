package com.hedera.services.store.models;

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

import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.state.merkle.internals.CopyOnWriteIds;
import com.hedera.services.txns.token.process.Dissociation;
import com.hedera.services.txns.validation.ContextOptionValidator;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hedera.services.state.merkle.internals.IdentityCodeUtils.buildAutomaticAssociationMetaData;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AccountTest {
	private Id subjectId = new Id(0, 0, 12345);
	private Id treasuryId = new Id(0, 0, 123456);
	private CopyOnWriteIds assocTokens = new CopyOnWriteIds(new long[] { 666, 0, 0, 777, 0, 0 });
	private long ownedNfts = 5;
	private int alreadyUsedAutoAssociations = 123;
	private int maxAutoAssociations = 1234;
	private int autoAssociationMetadata = buildAutomaticAssociationMetaData(maxAutoAssociations, alreadyUsedAutoAssociations);

	private Account subject;
	private Account treasuryAccount;
	private OptionValidator validator;

	@BeforeEach
	void setUp() {
		subject = new Account(subjectId);
		treasuryAccount = new Account(treasuryId);
		subject.setAssociatedTokens(assocTokens);
		subject.setAutoAssociationMetadata(autoAssociationMetadata);
		subject.setOwnedNfts(ownedNfts);

		validator = mock(ContextOptionValidator.class);
	}

	@Test
	void toStringAsExpected() {
		// given:
		final var desired = "Account{id=Id{shard=0, realm=0, num=12345}, expiry=0, balance=0, deleted=false, " +
				"tokens=[0.0.666, 0.0.777], ownedNfts=5, alreadyUsedAutoAssociations=123, maxAutoAssociations=1234}";

		// expect:
		assertEquals(desired, subject.toString());
	}

	@Test
	void dissociationHappyPathWorks() {
		// setup:
		final var alreadyAssocTokenId = new Id(0, 0, 666);
		final var dissociationRel = mock(Dissociation.class);
		final var tokenRel = mock(TokenRelationship.class);
		// and:
		final var expectedFinalTokens = "[0.0.777]";

		given(dissociationRel.dissociatingAccountId()).willReturn(subjectId);
		given(dissociationRel.dissociatedTokenId()).willReturn(alreadyAssocTokenId);
		given(dissociationRel.dissociatingAccountRel()).willReturn(tokenRel);
		given(tokenRel.isAutomaticAssociation()).willReturn(true);

		// when:
		subject.dissociateUsing(List.of(dissociationRel), validator);

		// then:
		verify(dissociationRel).updateModelRelsSubjectTo(validator);
		assertEquals(expectedFinalTokens, assocTokens.toReadableIdList());
		assertEquals(alreadyUsedAutoAssociations-1, subject.getAlreadyUsedAutomaticAssociations());
	}

	@Test
	void dissociationFailsInvalidIfRelDoesntReferToUs() {
		// setup:
		final var notOurId = new Id(0, 0, 666);
		final var dissociationRel = mock(Dissociation.class);

		given(dissociationRel.dissociatingAccountId()).willReturn(notOurId);

		// expect:
		assertFailsWith(() -> subject.dissociateUsing(List.of(dissociationRel), validator), FAIL_INVALID);
	}

	@Test
	void failsOnAssociatingWithAlreadyRelatedToken() {
		// setup:
		final var alreadyAssocToken = new Token(new Id(0, 0, 666));

		// expect:
		assertFailsWith(
				() -> subject.associateWith(List.of(alreadyAssocToken), 100, false),
				TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT);
	}

	@Test
	void cantAssociateWithMoreThanMax() {
		// setup:
		final var firstNewToken = new Token(new Id(0, 0, 888));
		final var secondNewToken = new Token(new Id(0, 0, 999));

		// when:
		assertFailsWith(
				() -> subject.associateWith(List.of(firstNewToken, secondNewToken), 3, false),
				TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED);
	}

	@Test
	void canAssociateWithNewToken() {
		// setup:
		final var firstNewToken = new Token(new Id(0, 0, 888));
		final var secondNewToken = new Token(new Id(0, 0, 999));
		final var expectedFinalTokens = "[0.0.666, 0.0.777, 0.0.888, 0.0.999]";
		subject.setAutoAssociationMetadata(autoAssociationMetadata);

		// when:
		subject.associateWith(List.of(firstNewToken, secondNewToken), 10, true);

		// expect:
		assertEquals(expectedFinalTokens, assocTokens.toReadableIdList());
	}

	@Test
	void accountEqualsCheck() {
		// setup:
		var account = new Account(subjectId);
		account.setAssociatedTokens(assocTokens);
		account.setExpiry(1000L);
		account.initBalance(100L);
		account.setOwnedNfts(1L);
		account.incrementOwnedNfts();
		account.setMaxAutomaticAssociations(123);
		account.setAlreadyUsedAutomaticAssociations(12);

		subject.setExpiry(1000L);
		subject.initBalance(100L);
		subject.setOwnedNfts(1L);
		subject.incrementOwnedNfts();
		subject.setAutoAssociationMetadata(account.getAutoAssociationMetadata());

		// when:
		var actualResult = subject.equals(account);

		// expect:
		assertEquals(account.getOwnedNfts(), subject.getOwnedNfts());
		// and:
		assertEquals(account.getId(), subject.getId());
		// and:
		assertEquals(account.getAssociatedTokens(), subject.getAssociatedTokens());
		// and:
		assertEquals(account.getMaxAutomaticAssociations(), subject.getMaxAutomaticAssociations());
		assertEquals(account.getAlreadyUsedAutomaticAssociations(), subject.getAlreadyUsedAutomaticAssociations());
		assertTrue(actualResult);
	}

	@Test
	void accountHashCodeCheck() {
		// setup:
		subject.setOwnedNfts(0);
		var otherSubject = new Account(subjectId);
		otherSubject.incrementOwnedNfts();
		otherSubject.setAssociatedTokens(assocTokens);

		subject.incrementOwnedNfts();
		otherSubject.setAutoAssociationMetadata(autoAssociationMetadata);
		// when:
		var actualResult = subject.hashCode();

		// expect:
		assertEquals(otherSubject.hashCode(), actualResult);
	}

	@Test
	void cannotAutomaticallyAssociateIfLimitReaches() {
		final var firstNewToken = new Token(new Id(0, 0, 888));
		subject.setMaxAutomaticAssociations(maxAutoAssociations);
		subject.setAlreadyUsedAutomaticAssociations(maxAutoAssociations);

		assertFailsWith(
				() -> subject.associateWith(List.of(firstNewToken), 10, true),
				NO_REMAINING_AUTOMATIC_ASSOCIATIONS);
	}

	@Test
	void incrementsTheAlreadyUsedAutoAssociationAsExpected() {
		final var firstNewToken = new Token(new Id(0, 0, 888));
		subject.setMaxAutomaticAssociations(maxAutoAssociations);
		subject.setAlreadyUsedAutomaticAssociations(maxAutoAssociations-1);

		subject.associateWith(List.of(firstNewToken), 10, true);

		assertEquals(maxAutoAssociations, subject.getAlreadyUsedAutomaticAssociations());
	}

	@Test
	void invalidValuesToAlreadyUsedAutoAssociationsFailAsExpected() {
		assertFailsWith(
				() -> subject.setAlreadyUsedAutomaticAssociations(maxAutoAssociations+1),
				NO_REMAINING_AUTOMATIC_ASSOCIATIONS);

		subject.setAlreadyUsedAutomaticAssociations(maxAutoAssociations);

		assertFailsWith(
				() -> subject.incrementUsedAutomaticAssocitions(),
				NO_REMAINING_AUTOMATIC_ASSOCIATIONS);

		subject.setAlreadyUsedAutomaticAssociations(0);

		assertFailsWith(
				() -> subject.decrementUsedAutomaticAssocitions(),
				NO_REMAINING_AUTOMATIC_ASSOCIATIONS);
	}

	private void assertFailsWith(Runnable something, ResponseCodeEnum status) {
		var ex = assertThrows(InvalidTransactionException.class, something::run);
		assertEquals(status, ex.getResponseCode());
	}
}
