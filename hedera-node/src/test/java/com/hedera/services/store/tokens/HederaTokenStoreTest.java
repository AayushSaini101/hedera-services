package com.hedera.services.store.tokens;

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

import com.google.protobuf.StringValue;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.utils.ImmutableKeyUtils;
import com.hedera.services.state.enums.TokenSupplyType;
import com.hedera.services.state.enums.TokenType;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleAccountTokens;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.tokens.views.UniqTokenViewsManager;
import com.hedera.test.factories.fees.CustomFeeBuilder;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FractionalFee;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.swirlds.fcmap.FCMap;
import com.swirlds.merkletree.MerklePair;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.hedera.services.ledger.accounts.BackingTokenRels.asTokenRel;
import static com.hedera.services.ledger.properties.AccountProperty.IS_DELETED;
import static com.hedera.services.ledger.properties.AccountProperty.NUM_NFTS_OWNED;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_FROZEN;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_KYC_GRANTED;
import static com.hedera.services.ledger.properties.TokenRelProperty.TOKEN_BALANCE;
import static com.hedera.services.state.merkle.MerkleEntityId.fromTokenId;
import static com.hedera.test.factories.fees.CustomFeeBuilder.fixedHbar;
import static com.hedera.test.factories.fees.CustomFeeBuilder.fixedHts;
import static com.hedera.test.factories.fees.CustomFeeBuilder.fractional;
import static com.hedera.test.factories.fees.CustomFeeBuilder.royaltyNoFallback;
import static com.hedera.test.factories.fees.CustomFeeBuilder.royaltyWithFallback;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.MISC_ACCOUNT_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.MISSING_TOKEN;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_ADMIN_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_FEE_SCHEDULE_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_FREEZE_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_KYC_KT;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_TREASURY_KT;
import static com.hedera.test.mocks.TestContextValidator.CONSENSUS_NOW;
import static com.hedera.test.mocks.TestContextValidator.TEST_VALIDATOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.BDDMockito.willThrow;

class HederaTokenStoreTest {
	private static final Key newKey = TxnHandlingScenario.TOKEN_REPLACE_KT.asKey();
	private static final JKey newFcKey = TxnHandlingScenario.TOKEN_REPLACE_KT.asJKeyUnchecked();
	private static final Key adminKey = TOKEN_ADMIN_KT.asKey();
	private static final Key kycKey = TOKEN_KYC_KT.asKey();
	private static final Key freezeKey = TOKEN_FREEZE_KT.asKey();
	private static final Key wipeKey = MISC_ACCOUNT_KT.asKey();
	private static final Key supplyKey = COMPLEX_KEY_ACCOUNT_KT.asKey();
	private static final Key feeScheduleKey = TOKEN_FEE_SCHEDULE_KT.asKey();

	private static final String symbol = "NOTHBAR";
	private static final String newSymbol = "REALLYSOM";
	private static final String newMemo = "NEWMEMO";
	private static final String memo = "TOKENMEMO";
	private static final String name = "TOKENNAME";
	private static final String newName = "NEWNAME";
	private static final int maxCustomFees = 5;
	private static final long expiry = CONSENSUS_NOW + 1_234_567L;
	private static final long newExpiry = CONSENSUS_NOW + 1_432_765L;
	private static final long totalSupply = 1_000_000L;
	private static final int decimals = 10;
	private static final long treasuryBalance = 50_000L;
	private static final long sponsorBalance = 1_000L;
	private static final int maxAutoAssociations = 1234;
	private static final int alreadyUsedAutoAssocitaions = 123;
	private static final TokenID misc = IdUtils.asToken("3.2.1");
	private static final TokenID nonfungible = IdUtils.asToken("4.3.2");
	private static final TokenID anotherMisc = IdUtils.asToken("6.4.2");
	private static final boolean freezeDefault = true;
	private static final boolean accountsKycGrantedByDefault = false;
	private static final long autoRenewPeriod = 500_000L;
	private static final long newAutoRenewPeriod = 2_000_000L;
	private static final AccountID autoRenewAccount = IdUtils.asAccount("1.2.5");
	private static final AccountID newAutoRenewAccount = IdUtils.asAccount("1.2.6");
	private static final AccountID primaryTreasury = IdUtils.asAccount("0.0.0");
	private static final AccountID treasury = IdUtils.asAccount("1.2.3");
	private static final AccountID newTreasury = IdUtils.asAccount("3.2.1");
	private static final AccountID sponsor = IdUtils.asAccount("1.2.666");
	private static final AccountID counterparty = IdUtils.asAccount("1.2.777");
	private static final AccountID feeCollector = treasury;
	private static final AccountID anotherFeeCollector = IdUtils.asAccount("1.2.777");
	private static final TokenID created = IdUtils.asToken("1.2.666666");
	private static final TokenID pending = IdUtils.asToken("1.2.555555");
	private static final int MAX_TOKENS_PER_ACCOUNT = 100;
	private static final int MAX_TOKEN_SYMBOL_UTF8_BYTES = 10;
	private static final int MAX_TOKEN_NAME_UTF8_BYTES = 100;
	private static final Pair<AccountID, TokenID> sponsorMisc = asTokenRel(sponsor, misc);
	private static final Pair<AccountID, TokenID> treasuryNft = asTokenRel(primaryTreasury, nonfungible);
	private static final Pair<AccountID, TokenID> newTreasuryNft = asTokenRel(newTreasury, nonfungible);
	private static final Pair<AccountID, TokenID> sponsorNft = asTokenRel(sponsor, nonfungible);
	private static final Pair<AccountID, TokenID> counterpartyNft = asTokenRel(counterparty, nonfungible);
	private static final Pair<AccountID, TokenID> treasuryMisc = asTokenRel(treasury, misc);
	private static final NftId aNft = new NftId(4, 3, 2, 1_234);
	private static final NftId tNft = new NftId(4, 3, 2, 1_2345);
	private static final Pair<AccountID, TokenID> anotherFeeCollectorMisc = asTokenRel(anotherFeeCollector, misc);
	private static final CustomFeeBuilder builder = new CustomFeeBuilder(feeCollector);
	private static final FractionalFee.Builder fractionalFee = fractional(15L, 100L)
			.setMaximumAmount(50)
			.setMinimumAmount(10);
	private static final FractionalFee.Builder fractionalFee_negative = fractional(-15L, 100L)
			.setMaximumAmount(50)
			.setMinimumAmount(10);
	private static final FractionalFee.Builder fractionalFee_negativeMin = fractional(15L, 100L)
			.setMaximumAmount(50)
			.setMinimumAmount(-10);
	private static final FractionalFee.Builder fractionalFee_greaterMin = fractional(15L, 100L)
			.setMaximumAmount(50)
			.setMinimumAmount(60);
	private static final FractionalFee.Builder fractionalFee_ZeroDiv = fractional(15L, 0L)
			.setMaximumAmount(50)
			.setMinimumAmount(10);
	private static final CustomFee customFixedFeeInHbar = builder.withFixedFee(fixedHbar(100L));
	private static final CustomFee customFixedFeeInHbar_ngeative = builder.withFixedFee(fixedHbar(-10L));
	private static final CustomFee customFixedFeeInHts = new CustomFeeBuilder(anotherFeeCollector)
			.withFixedFee(fixedHts(misc, 100L));
	private static final CustomFee customFixedFeeInHts_MissingDenom = new CustomFeeBuilder(anotherFeeCollector)
			.withFixedFee(fixedHts(MISSING_TOKEN, 100L));
	private static final CustomFee customFixedFeeInHts_NftDenom = new CustomFeeBuilder(anotherFeeCollector)
			.withFixedFee(fixedHts(nonfungible, 100L));
	private static final CustomFee customRoyaltyNoFallback = new CustomFeeBuilder(feeCollector)
			.withRoyaltyFee(royaltyNoFallback(11, 111));
	private static final CustomFee customRoyaltyHtsFallback = new CustomFeeBuilder(anotherFeeCollector)
			.withRoyaltyFee(royaltyWithFallback(11, 111, fixedHts(misc, 123)));
	private static final CustomFee customRoyaltyHtsFallback_ZeroDiv = new CustomFeeBuilder(anotherFeeCollector)
			.withRoyaltyFee(royaltyWithFallback(11, 0, fixedHts(misc, 123)));
	private static final CustomFee customRoyaltyHtsFallback_InvalidFraction = new CustomFeeBuilder(anotherFeeCollector)
			.withRoyaltyFee(royaltyWithFallback(11, 10, fixedHts(misc, 123)));
	private static final CustomFee customFixedFeeSameToken = builder.withFixedFee(fixedHts(50L));
	private static final CustomFee customFractionalFee = builder.withFractionalFee(fractionalFee);
	private static final CustomFee customFractionalFee_negative = builder.withFractionalFee(fractionalFee_negative);
	private static final CustomFee customFractionalFee_zeroDiv = builder.withFractionalFee(fractionalFee_ZeroDiv);
	private static final CustomFee customFractionalFee_negativeMin = builder.withFractionalFee(fractionalFee_negativeMin);
	private static final CustomFee customFractionalFee_greaterMin = builder.withFractionalFee(fractionalFee_greaterMin);
	private static final List<CustomFee> grpcCustomFees = List.of(
			customFixedFeeInHbar,
			customFixedFeeInHts,
			customFractionalFee,
			customFixedFeeSameToken
	);

	private EntityIdSource ids;
	private GlobalDynamicProperties properties;
	private UniqTokenViewsManager uniqTokenViewsManager;
	private FCMap<MerkleEntityId, MerkleToken> tokens;
	private TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger;
	private TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger;
	private TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger;
	private HederaLedger hederaLedger;

	private MerkleToken token;
	private MerkleToken nonfungibleToken;

	private HederaTokenStore subject;

	@BeforeEach
	void setup() {
		token = mock(MerkleToken.class);
		given(token.expiry()).willReturn(expiry);
		given(token.symbol()).willReturn(symbol);
		given(token.hasAutoRenewAccount()).willReturn(true);
		given(token.adminKey()).willReturn(Optional.of(TOKEN_ADMIN_KT.asJKeyUnchecked()));
		given(token.name()).willReturn(name);
		given(token.hasAdminKey()).willReturn(true);
		given(token.hasFeeScheduleKey()).willReturn(true);
		given(token.treasury()).willReturn(EntityId.fromGrpcAccountId(treasury));
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);

		nonfungibleToken = mock(MerkleToken.class);
		given(nonfungibleToken.hasAdminKey()).willReturn(true);
		given(nonfungibleToken.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);

		ids = mock(EntityIdSource.class);
		given(ids.newTokenId(sponsor)).willReturn(created);

		hederaLedger = mock(HederaLedger.class);

		nftsLedger = (TransactionalLedger<NftId, NftProperty, MerkleUniqueToken>) mock(TransactionalLedger.class);
		given(nftsLedger.get(aNft, NftProperty.OWNER)).willReturn(EntityId.fromGrpcAccountId(sponsor));
		given(nftsLedger.get(tNft, NftProperty.OWNER)).willReturn(EntityId.fromGrpcAccountId(primaryTreasury));
		given(nftsLedger.exists(aNft)).willReturn(true);
		given(nftsLedger.exists(tNft)).willReturn(true);

		accountsLedger = (TransactionalLedger<AccountID, AccountProperty, MerkleAccount>) mock(
				TransactionalLedger.class);
		given(accountsLedger.exists(treasury)).willReturn(true);
		given(accountsLedger.exists(anotherFeeCollector)).willReturn(true);
		given(accountsLedger.exists(autoRenewAccount)).willReturn(true);
		given(accountsLedger.exists(newAutoRenewAccount)).willReturn(true);
		given(accountsLedger.exists(primaryTreasury)).willReturn(true);
		given(accountsLedger.exists(sponsor)).willReturn(true);
		given(accountsLedger.exists(counterparty)).willReturn(true);
		given(accountsLedger.get(treasury, IS_DELETED)).willReturn(false);
		given(accountsLedger.get(autoRenewAccount, IS_DELETED)).willReturn(false);
		given(accountsLedger.get(newAutoRenewAccount, IS_DELETED)).willReturn(false);

		tokenRelsLedger = mock(TransactionalLedger.class);
		given(tokenRelsLedger.exists(sponsorMisc)).willReturn(true);
		given(tokenRelsLedger.exists(treasuryNft)).willReturn(true);
		given(tokenRelsLedger.exists(sponsorNft)).willReturn(true);
		given(tokenRelsLedger.exists(counterpartyNft)).willReturn(true);
		given(tokenRelsLedger.get(sponsorMisc, TOKEN_BALANCE)).willReturn(sponsorBalance);
		given(tokenRelsLedger.get(sponsorMisc, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(sponsorMisc, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.exists(treasuryMisc)).willReturn(true);
		given(tokenRelsLedger.exists(anotherFeeCollectorMisc)).willReturn(true);
		given(tokenRelsLedger.get(treasuryMisc, TOKEN_BALANCE)).willReturn(treasuryBalance);
		given(tokenRelsLedger.get(treasuryMisc, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(treasuryMisc, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.get(treasuryNft, TOKEN_BALANCE)).willReturn(123L);
		given(tokenRelsLedger.get(treasuryNft, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(treasuryNft, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.get(sponsorNft, TOKEN_BALANCE)).willReturn(123L);
		given(tokenRelsLedger.get(sponsorNft, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(sponsorNft, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.get(counterpartyNft, TOKEN_BALANCE)).willReturn(123L);
		given(tokenRelsLedger.get(counterpartyNft, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(counterpartyNft, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.get(newTreasuryNft, TOKEN_BALANCE)).willReturn(1L);

		tokens = (FCMap<MerkleEntityId, MerkleToken>) mock(FCMap.class);
		given(tokens.get(fromTokenId(created))).willReturn(token);
		given(tokens.containsKey(fromTokenId(misc))).willReturn(true);
		given(tokens.containsKey(fromTokenId(nonfungible))).willReturn(true);
		given(tokens.get(fromTokenId(misc))).willReturn(token);
		given(tokens.getForModify(fromTokenId(misc))).willReturn(token);
		given(tokens.get(fromTokenId(nonfungible))).willReturn(nonfungibleToken);
		given(tokens.getForModify(fromTokenId(nonfungible))).willReturn(nonfungibleToken);
		given(tokens.get(fromTokenId(tNft.tokenId())).treasury()).willReturn(
				EntityId.fromGrpcAccountId(primaryTreasury));

		properties = mock(GlobalDynamicProperties.class);
		given(properties.maxTokensPerAccount()).willReturn(MAX_TOKENS_PER_ACCOUNT);
		given(properties.maxTokenSymbolUtf8Bytes()).willReturn(MAX_TOKEN_SYMBOL_UTF8_BYTES);
		given(properties.maxTokenNameUtf8Bytes()).willReturn(MAX_TOKEN_NAME_UTF8_BYTES);
		given(properties.maxCustomFeesAllowed()).willReturn(maxCustomFees);

		uniqTokenViewsManager = mock(UniqTokenViewsManager.class);

		subject = new HederaTokenStore(
				ids, TEST_VALIDATOR, uniqTokenViewsManager, properties, () -> tokens, tokenRelsLedger, nftsLedger);
		subject.setAccountsLedger(accountsLedger);
		subject.setHederaLedger(hederaLedger);
		subject.knownTreasuries.put(treasury, new HashSet<>() {{
			add(misc);
		}});
	}

	@Test
	void rebuildsAsExpected() {
		final var captor = forClass(Consumer.class);
		subject.getKnownTreasuries().put(treasury, Set.of(anotherMisc));
		final var deletedToken = new MerkleToken();
		deletedToken.setDeleted(true);
		deletedToken.setTreasury(EntityId.fromGrpcAccountId(newTreasury));

		subject.rebuildViews();

		verify(tokens).forEachNode(captor.capture());

		final var visitor = captor.getValue();
		visitor.accept(new MerklePair<>(fromTokenId(misc), token));
		visitor.accept(new MerklePair<>(fromTokenId(anotherMisc), deletedToken));

		final var extant = subject.getKnownTreasuries();
		assertEquals(1, extant.size());
		assertTrue(extant.containsKey(treasury));
		assertEquals(extant.get(treasury), Set.of(misc));
	}

	@Test
	void injectsTokenRelsLedger() {
		verify(hederaLedger).setTokenRelsLedger(tokenRelsLedger);
		verify(hederaLedger).setNftsLedger(nftsLedger);
	}

	@Test
	void applicationRejectsMissing() {
		final var change = mock(Consumer.class);

		given(tokens.containsKey(fromTokenId(misc))).willReturn(false);

		assertThrows(IllegalArgumentException.class, () -> subject.apply(misc, change));
	}

	@Test
	void applicationAlwaysReplacesModifiableToken() {
		final var change = mock(Consumer.class);
		final var modifiableToken = mock(MerkleToken.class);
		given(tokens.getForModify(fromTokenId(misc))).willReturn(modifiableToken);
		willThrow(IllegalStateException.class).given(change).accept(modifiableToken);

		assertThrows(IllegalArgumentException.class, () -> subject.apply(misc, change));
	}

	@Test
	void applicationWorks() {
		final var change = mock(Consumer.class);
		final var inOrder = Mockito.inOrder(change, tokens);

		subject.apply(misc, change);

		inOrder.verify(tokens).getForModify(fromTokenId(misc));
		inOrder.verify(change).accept(token);
	}

	@Test
	void deletionWorksAsExpected() {
		TokenStore.DELETION.accept(token);

		verify(token).setDeleted(true);
	}

	@Test
	void rejectsDeletionMissingAdminKey() {
		given(token.adminKey()).willReturn(Optional.empty());

		final var outcome = subject.delete(misc);

		assertEquals(TOKEN_IS_IMMUTABLE, outcome);
	}

	@Test
	void rejectsDeletionTokenAlreadyDeleted() {
		given(token.isDeleted()).willReturn(true);

		final var outcome = subject.delete(misc);

		assertEquals(TOKEN_WAS_DELETED, outcome);
	}

	@Test
	void rejectsMissingDeletion() {
		final var mockSubject = mock(TokenStore.class);
		given(mockSubject.resolve(misc)).willReturn(TokenStore.MISSING_TOKEN);
		willCallRealMethod().given(mockSubject).delete(misc);

		final var outcome = mockSubject.delete(misc);

		assertEquals(INVALID_TOKEN_ID, outcome);
		verify(mockSubject, never()).apply(any(), any());
	}

	@Test
	void getDelegates() {
		assertSame(token, subject.get(misc));
	}

	@Test
	void getThrowsIseOnMissing() {
		given(tokens.containsKey(fromTokenId(misc))).willReturn(false);

		assertThrows(IllegalArgumentException.class, () -> subject.get(misc));
	}

	@Test
	void getCanReturnPending() {
		subject.pendingId = pending;
		subject.pendingCreation = token;

		assertSame(token, subject.get(pending));
	}

	@Test
	void existenceCheckUnderstandsPendingIdOnlyAppliesIfCreationPending() {
		assertFalse(subject.exists(HederaTokenStore.NO_PENDING_ID));
	}

	@Test
	void existenceCheckIncludesPending() {
		subject.pendingId = pending;

		assertTrue(subject.exists(pending));
	}

	@Test
	void freezingRejectsMissingAccount() {
		given(accountsLedger.exists(sponsor)).willReturn(false);

		final var status = subject.freeze(sponsor, misc);

		assertEquals(INVALID_ACCOUNT_ID, status);
	}

	@Test
	void associatingRejectsDeletedTokens() {
		given(token.isDeleted()).willReturn(true);

		final var status = subject.associate(sponsor, List.of(misc), false);

		assertEquals(TOKEN_WAS_DELETED, status);
	}

	@Test
	void associatingRejectsMissingToken() {
		given(tokens.containsKey(fromTokenId(misc))).willReturn(false);

		final var status = subject.associate(sponsor, List.of(misc), false);

		assertEquals(INVALID_TOKEN_ID, status);
	}

	@Test
	void associatingRejectsMissingAccounts() {
		given(accountsLedger.exists(sponsor)).willReturn(false);

		final var status = subject.associate(sponsor, List.of(misc), false);

		assertEquals(INVALID_ACCOUNT_ID, status);
	}

	@Test
	void realAssociationsExist() {
		assertTrue(subject.associationExists(sponsor, misc));
	}

	@Test
	void noAssociationsWithMissingAccounts() {
		given(accountsLedger.exists(sponsor)).willReturn(false);

		assertFalse(subject.associationExists(sponsor, misc));
	}

	@Test
	void associatingRejectsAlreadyAssociatedTokens() {
		final var tokens = mock(MerkleAccountTokens.class);
		given(tokens.includes(misc)).willReturn(true);
		given(hederaLedger.getAssociatedTokens(sponsor)).willReturn(tokens);

		final var status = subject.associate(sponsor, List.of(misc), false);

		assertEquals(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT, status);
	}

	@Test
	void associatingRejectsIfCappedAssociationsLimit() {
		final var tokens = mock(MerkleAccountTokens.class);
		given(tokens.includes(misc)).willReturn(false);
		given(tokens.numAssociations()).willReturn(MAX_TOKENS_PER_ACCOUNT);
		given(hederaLedger.getAssociatedTokens(sponsor)).willReturn(tokens);

		final var status = subject.associate(sponsor, List.of(misc), false);

		assertEquals(TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED, status);
		verify(tokens, never()).associateAll(any());
		verify(hederaLedger).setAssociatedTokens(sponsor, tokens);
	}

	@Test
	void associatingHappyPathWorks() {
		final var tokens = mock(MerkleAccountTokens.class);
		final var key = asTokenRel(sponsor, misc);
		given(tokens.includes(misc)).willReturn(false);
		given(hederaLedger.getAssociatedTokens(sponsor)).willReturn(tokens);
		given(hederaLedger.maxAutomaticAssociations(sponsor)).willReturn(maxAutoAssociations);
		given(hederaLedger.alreadyUsedAutomaticAssociations(sponsor)).willReturn(alreadyUsedAutoAssocitaions);
		given(token.hasKycKey()).willReturn(true);
		given(token.hasFreezeKey()).willReturn(true);
		given(token.accountsAreFrozenByDefault()).willReturn(true);

		final var status = subject.associate(sponsor, List.of(misc), true);

		assertEquals(OK, status);
		verify(tokens).associateAll(Set.of(misc));
		verify(hederaLedger).setAssociatedTokens(sponsor, tokens);
		verify(hederaLedger).addNewAssociationToList(any());
		verify(tokenRelsLedger).create(key);
		verify(tokenRelsLedger).set(key, TokenRelProperty.IS_FROZEN, true);
		verify(tokenRelsLedger).set(key, TokenRelProperty.IS_KYC_GRANTED, false);
		verify(tokenRelsLedger).set(key, TokenRelProperty.IS_AUTOMATIC_ASSOCIATION, true);
	}

	@Test
	void associatingFailsWhenAutoAssociationLimitReached() {
		final var tokens = mock(MerkleAccountTokens.class);
		given(tokens.includes(misc)).willReturn(false);
		given(tokens.includes(nonfungible)).willReturn(false);
		given(hederaLedger.getAssociatedTokens(sponsor)).willReturn(tokens);
		given(hederaLedger.maxAutomaticAssociations(sponsor)).willReturn(maxAutoAssociations);
		given(hederaLedger.alreadyUsedAutomaticAssociations(sponsor)).willReturn(maxAutoAssociations);

		// auto associate a fungible token
		var status = subject.associate(sponsor, List.of(misc), true);

		assertEquals(NO_REMAINING_AUTOMATIC_ASSOCIATIONS, status);

		// auto associate a fungibleUnique token
		status = subject.associate(sponsor, List.of(nonfungible), true);

		assertEquals(NO_REMAINING_AUTOMATIC_ASSOCIATIONS, status);
	}

	@Test
	void grantingKycRejectsMissingAccount() {
		given(accountsLedger.exists(sponsor)).willReturn(false);

		final var status = subject.grantKyc(sponsor, misc);

		assertEquals(INVALID_ACCOUNT_ID, status);
	}

	@Test
	void grantingKycRejectsDetachedAccount() {
		given(accountsLedger.exists(sponsor)).willReturn(true);
		given(hederaLedger.isDetached(sponsor)).willReturn(true);

		final var status = subject.grantKyc(sponsor, misc);

		assertEquals(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL, status);
	}

	@Test
	void grantingKycRejectsDeletedAccount() {
		given(accountsLedger.exists(sponsor)).willReturn(true);
		given(hederaLedger.isDeleted(sponsor)).willReturn(true);

		final var status = subject.grantKyc(sponsor, misc);

		assertEquals(ACCOUNT_DELETED, status);
	}

	@Test
	void revokingKycRejectsMissingAccount() {
		given(accountsLedger.exists(sponsor)).willReturn(false);

		final var status = subject.revokeKyc(sponsor, misc);

		assertEquals(INVALID_ACCOUNT_ID, status);
	}

	@Test
	void adjustingRejectsMissingAccount() {
		given(accountsLedger.exists(sponsor)).willReturn(false);

		final var status = subject.adjustBalance(sponsor, misc, 1);

		assertEquals(INVALID_ACCOUNT_ID, status);
	}

	@Test
	void changingOwnerRejectsMissingSender() {
		given(accountsLedger.exists(sponsor)).willReturn(false);

		final var status = subject.changeOwner(aNft, sponsor, counterparty);

		assertEquals(INVALID_ACCOUNT_ID, status);
	}

	@Test
	void changingOwnerRejectsMissingReceiver() {
		given(accountsLedger.exists(counterparty)).willReturn(false);

		final var status = subject.changeOwner(aNft, sponsor, counterparty);

		assertEquals(INVALID_ACCOUNT_ID, status);
	}

	@Test
	void changingOwnerRejectsMissingNftInstance() {
		given(nftsLedger.exists(aNft)).willReturn(false);

		final var status = subject.changeOwner(aNft, sponsor, counterparty);

		assertEquals(INVALID_NFT_ID, status);
	}

	@Test
	void changingOwnerRejectsUnassociatedReceiver() {
		given(tokenRelsLedger.exists(counterpartyNft)).willReturn(false);
		given(hederaLedger.maxAutomaticAssociations(counterparty)).willReturn(0);

		final var status = subject.changeOwner(aNft, sponsor, counterparty);

		assertEquals(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT, status);
	}

	@Test
	void changingOwnerRejectsIllegitimateOwner() {
		given(nftsLedger.get(aNft, NftProperty.OWNER)).willReturn(EntityId.fromGrpcAccountId(counterparty));

		final var status = subject.changeOwner(aNft, sponsor, counterparty);

		assertEquals(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO, status);
	}

	@Test
	void changingOwnerDoesTheExpected() {
		final long startSponsorNfts = 5;
		final long startCounterpartyNfts = 8;
		final long startSponsorANfts = 4;
		final long startCounterpartyANfts = 1;
		final var sender = EntityId.fromGrpcAccountId(sponsor);
		final var receiver = EntityId.fromGrpcAccountId(counterparty);
		final var muti = new MerkleUniqueTokenId(EntityId.fromGrpcTokenId(aNft.tokenId()), aNft.serialNo());
		given(accountsLedger.get(sponsor, NUM_NFTS_OWNED)).willReturn(startSponsorNfts);
		given(accountsLedger.get(counterparty, NUM_NFTS_OWNED)).willReturn(startCounterpartyNfts);
		given(tokenRelsLedger.get(sponsorNft, TOKEN_BALANCE)).willReturn(startSponsorANfts);
		given(tokenRelsLedger.get(counterpartyNft, TOKEN_BALANCE)).willReturn(startCounterpartyANfts);

		final var status = subject.changeOwner(aNft, sponsor, counterparty);

		assertEquals(OK, status);
		verify(nftsLedger).set(aNft, NftProperty.OWNER, receiver);
		verify(accountsLedger).set(sponsor, NUM_NFTS_OWNED, startSponsorNfts - 1);
		verify(accountsLedger).set(counterparty, NUM_NFTS_OWNED, startCounterpartyNfts + 1);
		verify(tokenRelsLedger).set(sponsorNft, TOKEN_BALANCE, startSponsorANfts - 1);
		verify(tokenRelsLedger).set(counterpartyNft, TOKEN_BALANCE, startCounterpartyANfts + 1);
		verify(uniqTokenViewsManager).exchangeNotice(muti, sender, receiver);
		verify(hederaLedger).updateOwnershipChanges(aNft, sponsor, counterparty);
	}

	@Test
	void changingOwnerDoesTheExpectedWithTreasuryReturn() {
		final long startTreasuryNfts = 5;
		final long startCounterpartyNfts = 8;
		final long startTreasuryTNfts = 4;
		final long startCounterpartyTNfts = 1;
		final var sender = EntityId.fromGrpcAccountId(counterparty);
		final var receiver = EntityId.fromGrpcAccountId(primaryTreasury);
		final var muti = new MerkleUniqueTokenId(EntityId.fromGrpcTokenId(tNft.tokenId()), tNft.serialNo());
		subject.knownTreasuries.put(primaryTreasury, new HashSet<>() {{
			add(nonfungible);
		}});
		given(accountsLedger.get(primaryTreasury, NUM_NFTS_OWNED)).willReturn(startTreasuryNfts);
		given(accountsLedger.get(counterparty, NUM_NFTS_OWNED)).willReturn(startCounterpartyNfts);
		given(tokenRelsLedger.get(treasuryNft, TOKEN_BALANCE)).willReturn(startTreasuryTNfts);
		given(tokenRelsLedger.get(counterpartyNft, TOKEN_BALANCE)).willReturn(startCounterpartyTNfts);
		given(nftsLedger.get(tNft, NftProperty.OWNER)).willReturn(EntityId.fromGrpcAccountId(counterparty));

		final var status = subject.changeOwner(tNft, counterparty, primaryTreasury);

		assertEquals(OK, status);
		verify(nftsLedger).set(tNft, NftProperty.OWNER, EntityId.MISSING_ENTITY_ID);
		verify(accountsLedger).set(primaryTreasury, NUM_NFTS_OWNED, startTreasuryNfts + 1);
		verify(accountsLedger).set(counterparty, NUM_NFTS_OWNED, startCounterpartyNfts - 1);
		verify(tokenRelsLedger).set(treasuryNft, TOKEN_BALANCE, startTreasuryTNfts + 1);
		verify(tokenRelsLedger).set(counterpartyNft, TOKEN_BALANCE, startCounterpartyTNfts - 1);
		verify(uniqTokenViewsManager).treasuryReturnNotice(muti, sender, receiver);
		verify(hederaLedger).updateOwnershipChanges(tNft, counterparty, primaryTreasury);
	}

	@Test
	void changingOwnerDoesTheExpectedWithTreasuryExit() {
		final long startTreasuryNfts = 5;
		final long startCounterpartyNfts = 8;
		final long startTreasuryTNfts = 4;
		final long startCounterpartyTNfts = 1;
		final var sender = EntityId.fromGrpcAccountId(primaryTreasury);
		final var receiver = EntityId.fromGrpcAccountId(counterparty);
		final var muti = new MerkleUniqueTokenId(EntityId.fromGrpcTokenId(tNft.tokenId()), tNft.serialNo());
		subject.knownTreasuries.put(primaryTreasury, new HashSet<>() {{
			add(nonfungible);
		}});
		given(accountsLedger.get(primaryTreasury, NUM_NFTS_OWNED)).willReturn(startTreasuryNfts);
		given(accountsLedger.get(counterparty, NUM_NFTS_OWNED)).willReturn(startCounterpartyNfts);
		given(tokenRelsLedger.get(treasuryNft, TOKEN_BALANCE)).willReturn(startTreasuryTNfts);
		given(tokenRelsLedger.get(counterpartyNft, TOKEN_BALANCE)).willReturn(startCounterpartyTNfts);

		final var status = subject.changeOwner(tNft, primaryTreasury, counterparty);

		assertEquals(OK, status);
		verify(nftsLedger).set(tNft, NftProperty.OWNER, receiver);
		verify(accountsLedger).set(primaryTreasury, NUM_NFTS_OWNED, startTreasuryNfts - 1);
		verify(accountsLedger).set(counterparty, NUM_NFTS_OWNED, startCounterpartyNfts + 1);
		verify(accountsLedger).set(counterparty, NUM_NFTS_OWNED, startCounterpartyNfts + 1);
		verify(tokenRelsLedger).set(treasuryNft, TOKEN_BALANCE, startTreasuryTNfts - 1);
		verify(tokenRelsLedger).set(counterpartyNft, TOKEN_BALANCE, startCounterpartyTNfts + 1);
		verify(uniqTokenViewsManager).treasuryExitNotice(muti, sender, receiver);
		verify(hederaLedger).updateOwnershipChanges(tNft, primaryTreasury, counterparty);
	}

	@Test
	void changingOwnerWildCardDoesTheExpectedWithTreasury() {
		final long startTreasuryNfts = 1;
		final long startCounterpartyNfts = 0;
		final long startTreasuryTNfts = 1;
		final long startCounterpartyTNfts = 0;
		subject.knownTreasuries.put(primaryTreasury, new HashSet<>() {{
			add(nonfungible);
		}});
		subject.knownTreasuries.put(counterparty, new HashSet<>() {{
			add(nonfungible);
		}});
		given(accountsLedger.get(primaryTreasury, NUM_NFTS_OWNED)).willReturn(startTreasuryNfts);
		given(accountsLedger.get(counterparty, NUM_NFTS_OWNED)).willReturn(startCounterpartyNfts);
		given(tokenRelsLedger.get(treasuryNft, TOKEN_BALANCE)).willReturn(startTreasuryTNfts);
		given(tokenRelsLedger.get(counterpartyNft, TOKEN_BALANCE)).willReturn(startCounterpartyTNfts);

		final var status = subject.changeOwnerWildCard(tNft, primaryTreasury, counterparty);

		assertEquals(OK, status);
		verify(accountsLedger).set(primaryTreasury, NUM_NFTS_OWNED, 0L);
		verify(accountsLedger).set(counterparty, NUM_NFTS_OWNED, 1L);
		verify(tokenRelsLedger).set(treasuryNft, TOKEN_BALANCE, startTreasuryTNfts - 1);
		verify(tokenRelsLedger).set(counterpartyNft, TOKEN_BALANCE, startCounterpartyTNfts + 1);
		verify(hederaLedger).updateOwnershipChanges(tNft, primaryTreasury, counterparty);
	}

	@Test
	void changingOwnerWildCardRejectsFromFreezeAndKYC() {
		given(tokenRelsLedger.get(treasuryNft, IS_FROZEN)).willReturn(true);

		final var status = subject.changeOwnerWildCard(tNft, primaryTreasury, counterparty);

		assertEquals(ACCOUNT_FROZEN_FOR_TOKEN, status);
	}

	@Test
	void changingOwnerWildCardRejectsToFreezeAndKYC() {
		given(tokenRelsLedger.get(counterpartyNft, IS_FROZEN)).willReturn(true);

		final var status = subject.changeOwnerWildCard(tNft, primaryTreasury, counterparty);

		assertEquals(ACCOUNT_FROZEN_FOR_TOKEN, status);
	}

	@Test
	void changingOwnerRejectsFromFreezeAndKYC() {
		given(tokenRelsLedger.get(treasuryNft, IS_FROZEN)).willReturn(true);

		final var status = subject.changeOwner(tNft, primaryTreasury, counterparty);

		assertEquals(ACCOUNT_FROZEN_FOR_TOKEN, status);
	}

	@Test
	void changingOwnerRejectsToFreezeAndKYC() {
		given(tokenRelsLedger.get(counterpartyNft, IS_FROZEN)).willReturn(true);

		final var status = subject.changeOwner(tNft, primaryTreasury, counterparty);

		assertEquals(ACCOUNT_FROZEN_FOR_TOKEN, status);
	}

	@Test
	void updateRejectsInvalidExpiry() {
		var op = updateWith(NO_KEYS, misc, true, true, false);
		op = op.toBuilder().setExpiry(Timestamp.newBuilder().setSeconds(expiry - 1)).build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(INVALID_EXPIRATION_TIME, outcome);
	}

	@Test
	void canExtendImmutableExpiry() {
		given(token.hasAdminKey()).willReturn(false);
		var op = updateWith(NO_KEYS, misc, false, false, false);
		op = op.toBuilder().setExpiry(Timestamp.newBuilder().setSeconds(expiry + 1_234)).build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(OK, outcome);
	}

	@Test
	void cannotUpdateImmutableTokenWithNewFeeScheduleKey() {
		given(token.hasAdminKey()).willReturn(false);
		given(token.hasFeeScheduleKey()).willReturn(true);
		var op = updateWith(NO_KEYS, misc, false, false, false);
		op = op.toBuilder()
				.setFeeScheduleKey(feeScheduleKey)
				.setExpiry(Timestamp.newBuilder().setSeconds(expiry + 1_234)).build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(TOKEN_IS_IMMUTABLE, outcome);
	}

	@Test
	void ifImmutableWillStayImmutable() {
		givenUpdateTarget(ALL_KEYS, token);
		given(token.hasFeeScheduleKey()).willReturn(false);
		var op = updateWith(ALL_KEYS, misc, false, false, false);
		op = op.toBuilder().setFeeScheduleKey(feeScheduleKey).build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(TOKEN_HAS_NO_FEE_SCHEDULE_KEY, outcome);
	}

	@Test
	void updateRejectsInvalidNewAutoRenew() {
		given(accountsLedger.exists(newAutoRenewAccount)).willReturn(false);
		final var op = updateWith(NO_KEYS, misc, true, true, false, true, false);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(INVALID_AUTORENEW_ACCOUNT, outcome);
	}

	@Test
	void updateRejectsInvalidNewAutoRenewPeriod() {
		var op = updateWith(NO_KEYS, misc, true, true, false, false, false);
		op = op.toBuilder().setAutoRenewPeriod(enduring(-1L)).build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(INVALID_RENEWAL_PERIOD, outcome);
	}

	@Test
	void updateRejectsMissingToken() {
		given(tokens.containsKey(fromTokenId(misc))).willReturn(false);
		givenUpdateTarget(ALL_KEYS, token);
		final var op = updateWith(ALL_KEYS, misc, true, true, true);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(INVALID_TOKEN_ID, outcome);
	}


	@Test
	void updateRejectsInappropriateKycKey() {
		givenUpdateTarget(NO_KEYS, token);
		final var op = updateWith(EnumSet.of(KeyType.KYC), misc, false, false, false);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(TOKEN_HAS_NO_KYC_KEY, outcome);
	}

	@Test
	void updateRejectsInappropriateFreezeKey() {
		givenUpdateTarget(NO_KEYS, token);
		final var op = updateWith(EnumSet.of(KeyType.FREEZE), misc, false, false, false);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(TOKEN_HAS_NO_FREEZE_KEY, outcome);
	}

	@Test
	void updateRejectsInappropriateWipeKey() {
		givenUpdateTarget(NO_KEYS, token);
		final var op = updateWith(EnumSet.of(KeyType.WIPE), misc, false, false, false);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(TOKEN_HAS_NO_WIPE_KEY, outcome);
	}

	@Test
	void updateRejectsInappropriateSupplyKey() {
		givenUpdateTarget(NO_KEYS, token);
		final var op = updateWith(EnumSet.of(KeyType.SUPPLY), misc, false, false, false);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(TOKEN_HAS_NO_SUPPLY_KEY, outcome);
	}

	@Test
	void updateRejectsZeroTokenBalanceKey() {
		final Set<TokenID> tokenSet = new HashSet<>();
		tokenSet.add(nonfungible);
		givenUpdateTarget(ALL_KEYS, nonfungibleToken);
		var op = updateWith(ALL_KEYS, nonfungible, true, true, true);
		op = op.toBuilder().setExpiry(Timestamp.newBuilder().setSeconds(0)).build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES, outcome);
	}

	@Test
	void treasuryRemovalForTokenRemovesKeyWhenEmpty() {
		final Set<TokenID> tokenSet = new HashSet<>(Arrays.asList(misc));
		subject.knownTreasuries.put(treasury, tokenSet);

		subject.removeKnownTreasuryForToken(treasury, misc);

		assertFalse(subject.knownTreasuries.containsKey(treasury));
		assertTrue(subject.knownTreasuries.isEmpty());
	}

	@Test
	void addKnownTreasuryWorks() {
		subject.addKnownTreasury(treasury, misc);

		assertTrue(subject.knownTreasuries.containsKey(treasury));
	}

	@Test
	void removeKnownTreasuryWorks() {
		final Set<TokenID> tokenSet = new HashSet<>(Arrays.asList(misc, anotherMisc));
		subject.knownTreasuries.put(treasury, tokenSet);

		subject.removeKnownTreasuryForToken(treasury, misc);

		assertTrue(subject.knownTreasuries.containsKey(treasury));
		assertEquals(1, subject.knownTreasuries.size());
		assertTrue(subject.knownTreasuries.get(treasury).contains(anotherMisc));
	}

	@Test
	void isKnownTreasuryWorks() {
		final Set<TokenID> tokenSet = new HashSet<>(Arrays.asList(misc));

		subject.knownTreasuries.put(treasury, tokenSet);

		assertTrue(subject.isKnownTreasury(treasury));
	}

	@Test
	void treasuriesServeWorks() {
		final Set<TokenID> tokenSet = new HashSet<>(List.of(anotherMisc, misc));

		subject.knownTreasuries.put(treasury, tokenSet);
		assertEquals(List.of(misc, anotherMisc), subject.listOfTokensServed(treasury));

		subject.knownTreasuries.remove(treasury);
		assertSame(Collections.emptyList(), subject.listOfTokensServed(treasury));
	}

	@Test
	void isTreasuryForTokenWorks() {
		final Set<TokenID> tokenSet = new HashSet<>(Arrays.asList(misc));

		subject.knownTreasuries.put(treasury, tokenSet);

		assertTrue(subject.isTreasuryForToken(treasury, misc));
	}

	@Test
	void isTreasuryForTokenReturnsFalse() {
		subject.knownTreasuries.clear();

		assertFalse(subject.isTreasuryForToken(treasury, misc));
	}

	@Test
	void throwsIfKnownTreasuryIsMissing() {
		assertThrows(IllegalArgumentException.class, () -> subject.removeKnownTreasuryForToken(null, misc));
	}

	@Test
	void throwsIfInvalidTreasury() {
		subject.knownTreasuries.clear();

		assertThrows(IllegalArgumentException.class, () -> subject.removeKnownTreasuryForToken(treasury, misc));
	}

	@Test
	void updateHappyPathIgnoresZeroExpiry() {
		subject.addKnownTreasury(treasury, misc);
		final Set<TokenID> tokenSet = new HashSet<>();
		tokenSet.add(misc);
		givenUpdateTarget(ALL_KEYS, token);
		var op = updateWith(ALL_KEYS, misc, true, true, true);
		op = op.toBuilder().setExpiry(Timestamp.newBuilder().setSeconds(0)).build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(OK, outcome);
		verify(token, never()).setExpiry(anyLong());
		assertFalse(subject.knownTreasuries.containsKey(treasury));
		assertEquals(subject.knownTreasuries.get(newTreasury), tokenSet);
	}

	@Test
	void updateRemovesAdminKeyWhenAppropos() {
		subject.addKnownTreasury(treasury, misc);
		final Set<TokenID> tokenSet = new HashSet<>();
		tokenSet.add(misc);
		givenUpdateTarget(EnumSet.noneOf(KeyType.class), token);
		final var op = updateWith(EnumSet.of(KeyType.EMPTY_ADMIN), misc, false, false, false);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(OK, outcome);
		verify(token).setAdminKey(MerkleToken.UNUSED_KEY);
	}

	@Test
	void updateHappyPathWorksForEverythingWithNewExpiry() {
		subject.addKnownTreasury(treasury, misc);
		final Set<TokenID> tokenSet = new HashSet<>();
		tokenSet.add(misc);
		givenUpdateTarget(ALL_KEYS, token);
		var op = updateWith(ALL_KEYS, misc, true, true, true);
		op = op.toBuilder()
				.setExpiry(Timestamp.newBuilder().setSeconds(newExpiry))
				.setFeeScheduleKey(newKey)
				.build();

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(OK, outcome);
		verify(token).setSymbol(newSymbol);
		verify(token).setName(newName);
		verify(token).setExpiry(newExpiry);
		verify(token).setTreasury(EntityId.fromGrpcAccountId(newTreasury));
		verify(token).setAdminKey(argThat((JKey k) -> JKey.equalUpToDecodability(k, newFcKey)));
		verify(token).setFreezeKey(argThat((JKey k) -> JKey.equalUpToDecodability(k, newFcKey)));
		verify(token).setKycKey(argThat((JKey k) -> JKey.equalUpToDecodability(k, newFcKey)));
		verify(token).setSupplyKey(argThat((JKey k) -> JKey.equalUpToDecodability(k, newFcKey)));
		verify(token).setWipeKey(argThat((JKey k) -> JKey.equalUpToDecodability(k, newFcKey)));
		verify(token).setFeeScheduleKey(argThat((JKey k) -> JKey.equalUpToDecodability(k, newFcKey)));
		assertFalse(subject.knownTreasuries.containsKey(treasury));
		assertEquals(subject.knownTreasuries.get(newTreasury), tokenSet);
	}

	@Test
	void updateHappyPathWorksWithNewMemo() {
		subject.addKnownTreasury(treasury, misc);
		givenUpdateTarget(ALL_KEYS, token);
		final var op = updateWith(NO_KEYS,
				misc,
				false,
				false,
				false,
				false,
				false,
				false,
				true);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(OK, outcome);
		verify(token).setMemo(newMemo);
	}

	@Test
	void updateHappyPathWorksWithNewMemoForNonfungible() {
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
		subject.addKnownTreasury(treasury, misc);
		givenUpdateTarget(ALL_KEYS, token);
		final var op = updateWith(NO_KEYS,
				misc,
				false,
				false,
				false,
				false,
				false,
				false,
				true);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(OK, outcome);
		verify(token).setMemo(newMemo);
	}

	@Test
	void updateHappyPathWorksWithNewAutoRenewAccount() {
		subject.addKnownTreasury(treasury, misc);
		givenUpdateTarget(ALL_KEYS, token);
		final var op = updateWith(ALL_KEYS, misc, true, true, true, true, true);

		final var outcome = subject.update(op, CONSENSUS_NOW);

		assertEquals(OK, outcome);
		verify(token).setAutoRenewAccount(EntityId.fromGrpcAccountId(newAutoRenewAccount));
		verify(token).setAutoRenewPeriod(newAutoRenewPeriod);
	}

	enum KeyType {
		WIPE, FREEZE, SUPPLY, KYC, ADMIN, EMPTY_ADMIN, FEE_SCHEDULE
	}

	private static final EnumSet<KeyType> NO_KEYS = EnumSet.noneOf(KeyType.class);
	private static final EnumSet<KeyType> ALL_KEYS = EnumSet.complementOf(EnumSet.of(KeyType.EMPTY_ADMIN));

	private TokenUpdateTransactionBody updateWith(
			final EnumSet<KeyType> keys,
			final TokenID tokenId,
			final boolean useNewSymbol,
			final boolean useNewName,
			final boolean useNewTreasury
	) {
		return updateWith(keys, tokenId, useNewName, useNewSymbol, useNewTreasury, false, false);
	}

	private TokenUpdateTransactionBody updateWith(
			final EnumSet<KeyType> keys,
			final TokenID tokenId,
			final boolean useNewSymbol,
			final boolean useNewName,
			final boolean useNewTreasury,
			final boolean useNewAutoRenewAccount,
			final boolean useNewAutoRenewPeriod
	) {
		return updateWith(
				keys,
				tokenId,
				useNewSymbol,
				useNewName,
				useNewTreasury,
				useNewAutoRenewAccount,
				useNewAutoRenewPeriod,
				false,
				false);
	}

	private TokenUpdateTransactionBody updateWith(
			final EnumSet<KeyType> keys,
			final TokenID tokenId,
			final boolean useNewSymbol,
			final boolean useNewName,
			final boolean useNewTreasury,
			final boolean useNewAutoRenewAccount,
			final boolean useNewAutoRenewPeriod,
			final boolean setInvalidKeys,
			final boolean useNewMemo
	) {
		final var invalidKey = Key.getDefaultInstance();
		final var op = TokenUpdateTransactionBody.newBuilder().setToken(tokenId);
		if (useNewSymbol) {
			op.setSymbol(newSymbol);
		}
		if (useNewName) {
			op.setName(newName);
		}
		if (useNewMemo) {
			op.setMemo(StringValue.newBuilder().setValue(newMemo).build());
		}
		if (useNewTreasury) {
			op.setTreasury(newTreasury);
		}
		if (useNewAutoRenewAccount) {
			op.setAutoRenewAccount(newAutoRenewAccount);
		}
		if (useNewAutoRenewPeriod) {
			op.setAutoRenewPeriod(enduring(newAutoRenewPeriod));
		}
		for (var key : keys) {
			switch (key) {
				case WIPE:
					op.setWipeKey(setInvalidKeys ? invalidKey : newKey);
					break;
				case FREEZE:
					op.setFreezeKey(setInvalidKeys ? invalidKey : newKey);
					break;
				case SUPPLY:
					op.setSupplyKey(setInvalidKeys ? invalidKey : newKey);
					break;
				case KYC:
					op.setKycKey(setInvalidKeys ? invalidKey : newKey);
					break;
				case ADMIN:
					op.setAdminKey(setInvalidKeys ? invalidKey : newKey);
					break;
				case EMPTY_ADMIN:
					op.setAdminKey(ImmutableKeyUtils.IMMUTABILITY_SENTINEL_KEY);
					break;
			}
		}
		return op.build();
	}

	private void givenUpdateTarget(final EnumSet<KeyType> keys, final MerkleToken token) {
		if (keys.contains(KeyType.WIPE)) {
			given(token.hasWipeKey()).willReturn(true);
		}
		if (keys.contains(KeyType.FREEZE)) {
			given(token.hasFreezeKey()).willReturn(true);
		}
		if (keys.contains(KeyType.SUPPLY)) {
			given(token.hasSupplyKey()).willReturn(true);
		}
		if (keys.contains(KeyType.KYC)) {
			given(token.hasKycKey()).willReturn(true);
		}
		if (keys.contains(KeyType.FEE_SCHEDULE)) {
			given(token.hasFeeScheduleKey()).willReturn(true);
		}
	}

	@Test
	void understandsPendingCreation() {
		assertFalse(subject.isCreationPending());

		subject.pendingId = misc;
		assertTrue(subject.isCreationPending());
	}

	@Test
	void adjustingRejectsMissingToken() {
		given(tokens.containsKey(fromTokenId(misc))).willReturn(false);

		final var status = subject.adjustBalance(sponsor, misc, 1);

		assertEquals(ResponseCodeEnum.INVALID_TOKEN_ID, status);
	}

	@Test
	void freezingRejectsUnfreezableToken() {
		given(token.freezeKey()).willReturn(Optional.empty());

		final var status = subject.freeze(treasury, misc);

		assertEquals(ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY, status);
	}

	@Test
	void grantingRejectsUnknowableToken() {
		given(token.kycKey()).willReturn(Optional.empty());

		final var status = subject.grantKyc(treasury, misc);

		assertEquals(ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY, status);
	}

	@Test
	void freezingRejectsDeletedToken() {
		givenTokenWithFreezeKey(true);
		given(token.isDeleted()).willReturn(true);

		final var status = subject.freeze(treasury, misc);

		assertEquals(ResponseCodeEnum.TOKEN_WAS_DELETED, status);
	}

	@Test
	void unfreezingInvalidWithoutFreezeKey() {
		final var status = subject.unfreeze(treasury, misc);

		assertEquals(TOKEN_HAS_NO_FREEZE_KEY, status);
	}

	@Test
	void performsValidFreeze() {
		givenTokenWithFreezeKey(false);

		subject.freeze(treasury, misc);

		verify(tokenRelsLedger).set(treasuryMisc, TokenRelProperty.IS_FROZEN, true);
	}

	private void givenTokenWithFreezeKey(boolean freezeDefault) {
		given(token.freezeKey()).willReturn(Optional.of(TOKEN_TREASURY_KT.asJKeyUnchecked()));
		given(token.accountsAreFrozenByDefault()).willReturn(freezeDefault);
	}

	@Test
	void adjustingRejectsDeletedToken() {
		given(token.isDeleted()).willReturn(true);

		final var status = subject.adjustBalance(treasury, misc, 1);

		assertEquals(ResponseCodeEnum.TOKEN_WAS_DELETED, status);
	}

	@Test
	void adjustingRejectsFungibleUniqueToken() {
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);

		final var status = subject.adjustBalance(treasury, misc, 1);

		assertEquals(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON, status);
	}

	@Test
	void refusesToAdjustFrozenRelationship() {
		given(tokenRelsLedger.get(treasuryMisc, IS_FROZEN)).willReturn(true);

		final var status = subject.adjustBalance(treasury, misc, -1);

		assertEquals(ACCOUNT_FROZEN_FOR_TOKEN, status);
	}

	@Test
	void refusesToAdjustRevokedKycRelationship() {
		given(tokenRelsLedger.get(treasuryMisc, IS_KYC_GRANTED)).willReturn(false);

		final var status = subject.adjustBalance(treasury, misc, -1);

		assertEquals(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN, status);
	}

	@Test
	void refusesInvalidAdjustment() {
		final var status = subject.adjustBalance(treasury, misc, -treasuryBalance - 1);

		assertEquals(INSUFFICIENT_TOKEN_BALANCE, status);
	}

	@Test
	void adjustmentFailsOnAutomaticAssociationLimitNotSet() {
		given(tokenRelsLedger.exists(anotherFeeCollectorMisc)).willReturn(false);
		given(hederaLedger.maxAutomaticAssociations(anotherFeeCollector)).willReturn(0);

		var status = subject.adjustBalance(anotherFeeCollector, misc, -1);
		assertEquals(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT, status);
	}

	@Test
	void adjustmentFailsOnAutomaticAssociationLimitReached() {
		final var tokens = mock(MerkleAccountTokens.class);
		given(tokenRelsLedger.exists(anotherFeeCollectorMisc)).willReturn(false);
		given(tokenRelsLedger.get(anotherFeeCollectorMisc, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(anotherFeeCollectorMisc, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.get(anotherFeeCollectorMisc, TOKEN_BALANCE)).willReturn(0L);
		given(hederaLedger.maxAutomaticAssociations(anotherFeeCollector)).willReturn(3);
		given(hederaLedger.alreadyUsedAutomaticAssociations(anotherFeeCollector)).willReturn(3);
		given(hederaLedger.getAssociatedTokens(anotherFeeCollector)).willReturn(tokens);
		given(tokens.includes(misc)).willReturn(false);

		var status = subject.adjustBalance(anotherFeeCollector, misc, 1);

		assertEquals(NO_REMAINING_AUTOMATIC_ASSOCIATIONS, status);
		verify(tokenRelsLedger, never()).set(anotherFeeCollectorMisc, TOKEN_BALANCE, 1L);
		verify(hederaLedger, never()).setAlreadyUsedAutomaticAssociations(anotherFeeCollector, 4);
	}

	@Test
	void adjustmentWorksAndIncrementsAlreadyUsedAutoAssociationCountForNewAssociation() {
		final var tokens = mock(MerkleAccountTokens.class);
		given(tokenRelsLedger.exists(anotherFeeCollectorMisc)).willReturn(false);
		given(tokenRelsLedger.get(anotherFeeCollectorMisc, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(anotherFeeCollectorMisc, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.get(anotherFeeCollectorMisc, TOKEN_BALANCE)).willReturn(0L);
		given(hederaLedger.maxAutomaticAssociations(anotherFeeCollector)).willReturn(5);
		given(hederaLedger.alreadyUsedAutomaticAssociations(anotherFeeCollector)).willReturn(3);
		given(hederaLedger.getAssociatedTokens(anotherFeeCollector)).willReturn(tokens);
		given(tokens.includes(misc)).willReturn(false);

		var status = subject.adjustBalance(anotherFeeCollector, misc, 1);

		assertEquals(OK, status);
		verify(tokenRelsLedger).set(anotherFeeCollectorMisc, TOKEN_BALANCE, 1L);
		verify(hederaLedger).setAlreadyUsedAutomaticAssociations(anotherFeeCollector, 4);
	}

	@Test
	void performsValidAdjustment() {
		subject.adjustBalance(treasury, misc, -1);

		verify(tokenRelsLedger).set(treasuryMisc, TOKEN_BALANCE, treasuryBalance - 1);
	}

	@Test
	void rollbackReclaimsIdAndClears() {
		subject.pendingId = created;
		subject.pendingCreation = token;

		subject.rollbackCreation();

		verify(tokens, never()).put(fromTokenId(created), token);
		verify(ids).reclaimLastId();
		assertSame(HederaTokenStore.NO_PENDING_ID, subject.pendingId);
		assertNull(subject.pendingCreation);
	}

	@Test
	void commitAndRollbackThrowIseIfNoPendingCreation() {
		assertThrows(IllegalStateException.class, subject::commitCreation);
		assertThrows(IllegalStateException.class, subject::rollbackCreation);
	}

	@Test
	void commitPutsToMapAndClears() {
		subject.pendingId = created;
		subject.pendingCreation = token;

		subject.commitCreation();

		verify(tokens).put(fromTokenId(created), token);
		assertSame(HederaTokenStore.NO_PENDING_ID, subject.pendingId);
		assertNull(subject.pendingCreation);
		assertTrue(subject.isKnownTreasury(treasury));
		assertEquals(Set.of(created, misc), subject.knownTreasuries.get(treasury));
	}

	TokenCreateTransactionBody.Builder fullyValidTokenCreateAttempt() {
		return TokenCreateTransactionBody.newBuilder()
				.setExpiry(Timestamp.newBuilder().setSeconds(expiry))
				.setMemo(memo)
				.setAdminKey(adminKey)
				.setKycKey(kycKey)
				.setFreezeKey(freezeKey)
				.setWipeKey(wipeKey)
				.setSupplyKey(supplyKey)
				.setFeeScheduleKey(feeScheduleKey)
				.setSymbol(symbol)
				.setName(name)
				.setInitialSupply(totalSupply)
				.setTreasury(treasury)
				.setDecimals(decimals)
				.setFreezeDefault(freezeDefault)
				.addAllCustomFees(grpcCustomFees);
	}

	private MerkleToken buildFullyValidExpectedToken() {
		final var expected = new MerkleToken(
				CONSENSUS_NOW + autoRenewPeriod,
				totalSupply,
				decimals,
				symbol,
				name,
				freezeDefault,
				accountsKycGrantedByDefault,
				new EntityId(treasury.getShardNum(), treasury.getRealmNum(), treasury.getAccountNum()));

		expected.setAutoRenewAccount(EntityId.fromGrpcAccountId(autoRenewAccount));
		expected.setAutoRenewPeriod(autoRenewPeriod);
		expected.setAdminKey(TOKEN_ADMIN_KT.asJKeyUnchecked());
		expected.setFreezeKey(TOKEN_FREEZE_KT.asJKeyUnchecked());
		expected.setKycKey(TOKEN_KYC_KT.asJKeyUnchecked());
		expected.setWipeKey(MISC_ACCOUNT_KT.asJKeyUnchecked());
		expected.setSupplyKey(COMPLEX_KEY_ACCOUNT_KT.asJKeyUnchecked());
		expected.setFeeScheduleKey(TOKEN_FEE_SCHEDULE_KT.asJKeyUnchecked());
		expected.setTokenType(TokenType.FUNGIBLE_COMMON);
		expected.setSupplyType(TokenSupplyType.INFINITE);
		expected.setMemo(memo);
		expected.setFeeScheduleFrom(grpcCustomFees, EntityId.fromGrpcTokenId(created));

		return expected;
	}

	private Duration enduring(final long secs) {
		return Duration.newBuilder().setSeconds(secs).build();
	}

	@Test
	void rejectsMissingTokenIdCustomFeeUpdates() {
		final var op = updateFeeScheduleWithMissingTokenId();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(INVALID_TOKEN_ID, result);
	}

	@Test
	void rejectsTooLongCustomFeeUpdates() {
		final var op = updateFeeScheduleWith();
		given(properties.maxCustomFeesAllowed()).willReturn(1);

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_FEES_LIST_TOO_LONG, result);
	}

	@Test
	void rejectsFeesUpdatedWithEmptyFees() {
		final var op = updateFeeScheduleWithEmptyFees();
		given(token.grpcFeeSchedule()).willReturn(List.of());

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES, result);
	}

	@Test
	void rejectsFeesUpdatedWithUnassociatedFeeCollector() {
		final var op = updateFeeScheduleWithUnassociatedFeeCollector();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR, result);
	}

	@Test
	void rejectsFeesUpdatedWithMissingFeeCollector() {
		given(accountsLedger.exists(feeCollector)).willReturn(false);
		final var op = updateFeeScheduleWith();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(INVALID_CUSTOM_FEE_COLLECTOR, result);
	}

	@Test
	void rejectsFeesUpdatedWithIncompleteFee() {
		final var op = updateFeeScheduleWithIncompleteFee();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_FEE_NOT_FULLY_SPECIFIED, result);
	}

	@Test
	void canOnlyUpdateTokensWithFeeScheduleKey() {
		given(token.hasFeeScheduleKey()).willReturn(false);
		final var op = updateFeeScheduleWith();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(TOKEN_HAS_NO_FEE_SCHEDULE_KEY, result);
	}

	@Test
	void cannotUseFractionalFeeWithNonfungibleUpdateTarget() {
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
		final var op = updateFeeScheduleWithOnlyFractional();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON, result);
	}

	@Test
	void cannotUseRoyaltyFeeWithFungibleCommonUpdateTarget() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithOnlyRoyaltyHtsFallback();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE, result);
	}

	@Test
	void canUseRoyaltyFeeWithNoFallBackFees() {
		given(token.tokenType())
				.willReturn(TokenType.NON_FUNGIBLE_UNIQUE)
				.willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithRoyaltyHtsNoFallback();

		final var result = subject.updateFeeSchedule(op);

		verify(token).setFeeScheduleFrom(List.of(customRoyaltyNoFallback), EntityId.fromGrpcTokenId(misc));
		assertEquals(OK, result);
	}

	@Test
	void cannotUseRoyaltyFeeWithZeroDivFraction() {
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
		final var op = updateFeeScheduleWithOnlyRoyaltyHtsFallbackZeroDiv();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(FRACTION_DIVIDES_BY_ZERO, result);
	}

	@Test
	void cannotUseRoyaltyFeeWithInvalidFraction() {
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
		final var op = updateFeeScheduleWithOnlyRoyaltyHtsFallbackInvalidFraction();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(ROYALTY_FRACTION_CANNOT_EXCEED_ONE, result);
	}

	@Test
	void cannotUseNegativeAmountInCustomFee() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithNegativeValue();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_FEE_MUST_BE_POSITIVE, result);
	}

	@Test
	void cannotUseFixedCustomFeeWithMissingToken() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithFixedFeeMissingToken();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(INVALID_TOKEN_ID_IN_CUSTOM_FEES, result);
	}

	@Test
	void cannotUseFixedCustomFeeWithNftTokenAsDenom() {
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
		given(tokens.get(fromTokenId(nonfungible))).willReturn(token);
		final var op = updateFeeScheduleWithFixedFeeNftToken();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON, result);
	}

	@Test
	void cannotUseFixedCustomFeeWithNonAssociatedTokenToCollector() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		given(tokenRelsLedger.exists(anotherFeeCollectorMisc)).willReturn(false);
		final var op = updateFeeScheduleWithFixedHtsFee();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR, result);
	}

	@Test
	void cannotUseNegativeAmountInCustomFractionalFee() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithNegativeFractionalFee();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_FEE_MUST_BE_POSITIVE, result);
	}

	@Test
	void cannotUseInvalidZeroDivisionInCustomFractionalFee() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithZeroDivisonFractionalFee();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(FRACTION_DIVIDES_BY_ZERO, result);
	}

	@Test
	void cannotUseNegativeMinInCustomFractionalFee() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithNegativeMinInFractionalFee();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(CUSTOM_FEE_MUST_BE_POSITIVE, result);
	}

	@Test
	void cannotUseGreaterMinInCustomFractionalFee() {
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithGreaterMinInFractionalFee();

		final var result = subject.updateFeeSchedule(op);

		assertEquals(FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT, result);
	}

	@Test
	void happyPathCustomFeesUpdated() {
		final var op = updateFeeScheduleWith();

		final var result = subject.updateFeeSchedule(op);

		verify(token).setFeeScheduleFrom(grpcCustomFees, EntityId.fromGrpcTokenId(misc));
		assertEquals(OK, result);
	}

	@Test
	void happyPathCustomRoyaltyFeesUpdated() {
		given(token.tokenType())
				.willReturn(TokenType.NON_FUNGIBLE_UNIQUE)
				.willReturn(TokenType.FUNGIBLE_COMMON);
		final var op = updateFeeScheduleWithOnlyRoyaltyHtsFallback();

		final var result = subject.updateFeeSchedule(op);

		verify(token).setFeeScheduleFrom(List.of(customRoyaltyHtsFallback), EntityId.fromGrpcTokenId(misc));
		assertEquals(OK, result);
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithNegativeValue() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFixedFeeInHbar_ngeative));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithFixedFeeMissingToken() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFixedFeeInHts_MissingDenom));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithFixedFeeNftToken() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(nonfungible)
				.addAllCustomFees(List.of(customFixedFeeInHts_NftDenom));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithFixedHtsFee() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFixedFeeInHts));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithNegativeFractionalFee() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFractionalFee_negative));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithZeroDivisonFractionalFee() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFractionalFee_zeroDiv));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithNegativeMinInFractionalFee() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFractionalFee_negativeMin));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithGreaterMinInFractionalFee() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFractionalFee_greaterMin));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithEmptyFees() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of());
		return op.build();
	}


	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithMissingTokenId() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.addAllCustomFees(grpcCustomFees);
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWith() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(grpcCustomFees);
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithIncompleteFee() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(builder.withOnlyFeeCollector()));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithOnlyFractional() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customFractionalFee));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithOnlyRoyaltyHtsFallback() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customRoyaltyHtsFallback));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithRoyaltyHtsNoFallback() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customRoyaltyNoFallback));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithOnlyRoyaltyHtsFallbackZeroDiv() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customRoyaltyHtsFallback_ZeroDiv));
		return op.build();
	}

	private static final TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithOnlyRoyaltyHtsFallbackInvalidFraction() {
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(customRoyaltyHtsFallback_InvalidFraction));
		return op.build();
	}

	private TokenFeeScheduleUpdateTransactionBody updateFeeScheduleWithUnassociatedFeeCollector() {
		final var someFeeCollector = IdUtils.asAccount("1.2.778");
		given(accountsLedger.exists(someFeeCollector)).willReturn(true);
		final var rel = asTokenRel(someFeeCollector, misc);
		given(tokenRelsLedger.exists(rel)).willReturn(false);
		final var feeWithUnassociatedFeeCollector = new CustomFeeBuilder(someFeeCollector)
				.withFractionalFee(fractionalFee);
		final var op = TokenFeeScheduleUpdateTransactionBody.newBuilder()
				.setTokenId(misc)
				.addAllCustomFees(List.of(feeWithUnassociatedFeeCollector));

		return op.build();
	}
}
