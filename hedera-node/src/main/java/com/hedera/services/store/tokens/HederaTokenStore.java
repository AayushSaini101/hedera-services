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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.utils.ImmutableKeyUtils;
import com.hedera.services.state.enums.TokenType;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.FcTokenAssociation;
import com.hedera.services.store.HederaStore;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.tokens.views.UniqTokenViewsManager;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.Fraction;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.ledger.accounts.BackingTokenRels.asTokenRel;
import static com.hedera.services.ledger.properties.AccountProperty.NUM_NFTS_OWNED;
import static com.hedera.services.ledger.properties.NftProperty.OWNER;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_FROZEN;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_KYC_GRANTED;
import static com.hedera.services.ledger.properties.TokenRelProperty.TOKEN_BALANCE;
import static com.hedera.services.state.enums.TokenType.FUNGIBLE_COMMON;
import static com.hedera.services.state.enums.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.services.state.merkle.MerkleEntityId.fromTokenId;
import static com.hedera.services.state.merkle.MerkleToken.UNUSED_KEY;
import static com.hedera.services.state.submerkle.EntityId.fromGrpcAccountId;
import static com.hedera.services.state.submerkle.EntityId.fromGrpcTokenId;
import static com.hedera.services.utils.EntityIdUtils.readableId;
import static com.hedera.services.utils.MiscUtils.asFcKeyUnchecked;
import static com.hedera.services.utils.MiscUtils.asUsableFcKey;
import static com.hedera.services.utils.MiscUtils.forEach;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
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
import static java.util.stream.Collectors.toList;

/**
 * Provides a managing store for arbitrary tokens.
 */
@Singleton
public class HederaTokenStore extends HederaStore implements TokenStore {
	static final TokenID NO_PENDING_ID = TokenID.getDefaultInstance();

	private static final Predicate<Key> REMOVES_ADMIN_KEY = ImmutableKeyUtils::signalsKeyRemoval;

	private final OptionValidator validator;
	private final UniqTokenViewsManager uniqTokenViewsManager;
	private final GlobalDynamicProperties properties;
	private final Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens;
	private final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger;
	private final TransactionalLedger<
			Pair<AccountID, TokenID>,
			TokenRelProperty,
			MerkleTokenRelStatus> tokenRelsLedger;
	Map<AccountID, Set<TokenID>> knownTreasuries = new HashMap<>();

	TokenID pendingId = NO_PENDING_ID;
	MerkleToken pendingCreation;

	@Inject
	public HederaTokenStore(
			final EntityIdSource ids,
			final OptionValidator validator,
			final UniqTokenViewsManager uniqTokenViewsManager,
			final GlobalDynamicProperties properties,
			final Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens,
			final TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger,
			final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger
	) {
		super(ids);
		this.tokens = tokens;
		this.validator = validator;
		this.properties = properties;
		this.nftsLedger = nftsLedger;
		this.tokenRelsLedger = tokenRelsLedger;
		this.uniqTokenViewsManager = uniqTokenViewsManager;
		/* Known-treasuries view is re-built on restart or reconnect */
	}

	@Override
	public void rebuildViews() {
		knownTreasuries.clear();
		rebuildViewOfKnownTreasuries();
	}

	private void rebuildViewOfKnownTreasuries() {
		forEach(tokens.get(), (key, value) -> {
			/* A deleted token's treasury is no longer bound by ACCOUNT_IS_TREASURY restrictions. */
			if (!value.isDeleted()) {
				addKnownTreasury(value.treasury().toGrpcAccountId(), key.toTokenId());
			}
		});
	}

	@Override
	public List<TokenID> listOfTokensServed(final AccountID treasury) {
		if (!isKnownTreasury(treasury)) {
			return Collections.emptyList();
		} else {
			return knownTreasuries.get(treasury).stream()
					.sorted(HederaLedger.TOKEN_ID_COMPARATOR)
					.collect(toList());
		}
	}

	@Override
	public boolean isCreationPending() {
		return pendingId != NO_PENDING_ID;
	}

	@Override
	public void setHederaLedger(final HederaLedger hederaLedger) {
		hederaLedger.setNftsLedger(nftsLedger);
		hederaLedger.setTokenRelsLedger(tokenRelsLedger);
		super.setHederaLedger(hederaLedger);
	}

	@Override
	public ResponseCodeEnum associate(AccountID aId, List<TokenID> tokens, boolean automaticAssociation) {
		return fullySanityChecked(true, aId, tokens, (account, tokenIds) -> {
			final var accountTokens = hederaLedger.getAssociatedTokens(aId);
			for (var id : tokenIds) {
				if (accountTokens.includes(id)) {
					return TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
				}
			}
			var validity = OK;
			if ((accountTokens.numAssociations() + tokenIds.size()) > properties.maxTokensPerAccount()) {
				validity = TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
			} else {
				var maxAutomaticAssociations = hederaLedger.maxAutomaticAssociations(aId);
				var alreadyUsedAutomaticAssociations = hederaLedger.alreadyUsedAutomaticAssociations(aId);

				if (automaticAssociation && alreadyUsedAutomaticAssociations >= maxAutomaticAssociations) {
					validity = NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
				}

				if (validity == OK) {
					accountTokens.associateAll(new HashSet<>(tokenIds));
					for (var id : tokenIds) {
						final var relationship = asTokenRel(aId, id);
						tokenRelsLedger.create(relationship);
						final var token = get(id);
						tokenRelsLedger.set(
								relationship,
								TokenRelProperty.IS_FROZEN,
								token.hasFreezeKey() && token.accountsAreFrozenByDefault());
						tokenRelsLedger.set(
								relationship,
								TokenRelProperty.IS_KYC_GRANTED,
								!token.hasKycKey());
						tokenRelsLedger.set(
								relationship,
								TokenRelProperty.IS_AUTOMATIC_ASSOCIATION,
								automaticAssociation);

						hederaLedger.addNewAssociationToList(
								new FcTokenAssociation(id.getTokenNum(), aId.getAccountNum()));
						if (automaticAssociation) {
							hederaLedger.setAlreadyUsedAutomaticAssociations(aId, alreadyUsedAutomaticAssociations + 1);
						}
					}
				}
			}
			hederaLedger.setAssociatedTokens(aId, accountTokens);
			return validity;
		});
	}

	@Override
	public boolean associationExists(final AccountID aId, final TokenID tId) {
		return checkExistence(aId, tId) == OK && tokenRelsLedger.exists(asTokenRel(aId, tId));
	}

	@Override
	public boolean exists(final TokenID id) {
		return (isCreationPending() && pendingId.equals(id)) || tokens.get().containsKey(fromTokenId(id));
	}

	@Override
	public MerkleToken get(final TokenID id) {
		throwIfMissing(id);

		return pendingId.equals(id) ? pendingCreation : tokens.get().get(fromTokenId(id));
	}

	@Override
	public void apply(final TokenID id, final Consumer<MerkleToken> change) {
		throwIfMissing(id);

		final var key = fromTokenId(id);
		final var token = tokens.get().getForModify(key);
		try {
			change.accept(token);
		} catch (Exception internal) {
			throw new IllegalArgumentException("Token change failed unexpectedly!", internal);
		}
	}

	@Override
	public ResponseCodeEnum grantKyc(final AccountID aId, final TokenID tId) {
		return setHasKyc(aId, tId, true);
	}

	@Override
	public ResponseCodeEnum revokeKyc(final AccountID aId, final TokenID tId) {
		return setHasKyc(aId, tId, false);
	}

	@Override
	public ResponseCodeEnum unfreeze(final AccountID aId, final TokenID tId) {
		return setIsFrozen(aId, tId, false);
	}

	@Override
	public ResponseCodeEnum freeze(final AccountID aId, final TokenID tId) {
		return setIsFrozen(aId, tId, true);
	}

	private ResponseCodeEnum setHasKyc(final AccountID aId, final TokenID tId, final boolean value) {
		return manageFlag(
				aId,
				tId,
				value,
				TOKEN_HAS_NO_KYC_KEY,
				TokenRelProperty.IS_KYC_GRANTED,
				MerkleToken::kycKey);
	}

	private ResponseCodeEnum setIsFrozen(final AccountID aId, final TokenID tId, final boolean value) {
		return manageFlag(
				aId,
				tId,
				value,
				TOKEN_HAS_NO_FREEZE_KEY,
				TokenRelProperty.IS_FROZEN,
				MerkleToken::freezeKey);
	}

	@Override
	public ResponseCodeEnum adjustBalance(final AccountID aId, final TokenID tId, final long adjustment) {
		return sanityCheckedFungibleCommon(aId, tId, token -> tryAdjustment(aId, tId, adjustment));
	}

	@Override
	public ResponseCodeEnum changeOwner(final NftId nftId, final AccountID from, final AccountID to) {
		final var tId = nftId.tokenId();
		return sanityChecked(false, from, to, tId, token -> {
			if (!nftsLedger.exists(nftId)) {
				return INVALID_NFT_ID;
			}

			final var fromFreezeAndKycValidity = checkRelFrozenAndKycProps(from, tId);
			if (fromFreezeAndKycValidity != OK) {
				return fromFreezeAndKycValidity;
			}
			final var toFreezeAndKycValidity = checkRelFrozenAndKycProps(to, tId);
			if (toFreezeAndKycValidity != OK) {
				return toFreezeAndKycValidity;
			}

			var owner = (EntityId) nftsLedger.get(nftId, OWNER);
			if (owner.equals(fromGrpcAccountId(AccountID.getDefaultInstance()))) {
				final var tid = nftId.tokenId();
				owner = this.tokens.get().get(
						new MerkleEntityId(tid.getShardNum(), tid.getRealmNum(), tid.getTokenNum())).treasury();
			}
			if (!owner.matches(from)) {
				return SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
			}

			updateLedgers(nftId, from, to, tId, owner);
			return OK;
		});
	}

	private void updateLedgers(
			final NftId nftId,
			final AccountID from,
			final AccountID to,
			final TokenID tId,
			final EntityId owner
	) {
		final var nftType = nftId.tokenId();
		final var fromRel = asTokenRel(from, nftType);
		final var toRel = asTokenRel(to, nftType);
		final var fromNftsOwned = (long) accountsLedger.get(from, NUM_NFTS_OWNED);
		final var fromThisNftsOwned = (long) tokenRelsLedger.get(fromRel, TOKEN_BALANCE);
		final var toNftsOwned = (long) accountsLedger.get(to, NUM_NFTS_OWNED);
		final var toThisNftsOwned = (long) tokenRelsLedger.get(asTokenRel(to, nftType), TOKEN_BALANCE);
		final var isTreasuryReturn = isTreasuryForToken(to, tId);
		if (isTreasuryReturn) {
			nftsLedger.set(nftId, OWNER, EntityId.MISSING_ENTITY_ID);
		} else {
			nftsLedger.set(nftId, OWNER, EntityId.fromGrpcAccountId(to));
		}

		accountsLedger.set(from, NUM_NFTS_OWNED, fromNftsOwned - 1);
		accountsLedger.set(to, NUM_NFTS_OWNED, toNftsOwned + 1);
		tokenRelsLedger.set(fromRel, TOKEN_BALANCE, fromThisNftsOwned - 1);
		tokenRelsLedger.set(toRel, TOKEN_BALANCE, toThisNftsOwned + 1);

		final var merkleNftId = new MerkleUniqueTokenId(fromGrpcTokenId(nftId.tokenId()), nftId.serialNo());
		final var receiver = fromGrpcAccountId(to);
		if (isTreasuryReturn) {
			uniqTokenViewsManager.treasuryReturnNotice(merkleNftId, owner, receiver);
		} else {
			final var isTreasuryExit = isTreasuryForToken(from, tId);
			if (isTreasuryExit) {
				uniqTokenViewsManager.treasuryExitNotice(merkleNftId, owner, receiver);
			} else {
				uniqTokenViewsManager.exchangeNotice(merkleNftId, owner, receiver);
			}
		}
		hederaLedger.updateOwnershipChanges(nftId, from, to);
	}

	@Override
	public ResponseCodeEnum changeOwnerWildCard(final NftId nftId, final AccountID from, final AccountID to) {
		final var tId = nftId.tokenId();
		return sanityChecked(false, from, to, tId, token -> {
			final var fromFreezeAndKycValidity = checkRelFrozenAndKycProps(from, tId);
			if (fromFreezeAndKycValidity != OK) {
				return fromFreezeAndKycValidity;
			}
			final var toFreezeAndKycValidity = checkRelFrozenAndKycProps(to, tId);
			if (toFreezeAndKycValidity != OK) {
				return toFreezeAndKycValidity;
			}

			final var nftType = nftId.tokenId();
			final var fromRel = asTokenRel(from, nftType);
			final var toRel = asTokenRel(to, nftType);
			final var fromNftsOwned = (long) accountsLedger.get(from, NUM_NFTS_OWNED);
			final var fromThisNftsOwned = (long) tokenRelsLedger.get(fromRel, TOKEN_BALANCE);
			final var toNftsOwned = (long) accountsLedger.get(to, NUM_NFTS_OWNED);
			final var toThisNftsOwned = (long) tokenRelsLedger.get(toRel, TOKEN_BALANCE);

			accountsLedger.set(from, NUM_NFTS_OWNED, fromNftsOwned - fromThisNftsOwned);
			accountsLedger.set(to, NUM_NFTS_OWNED, toNftsOwned + fromThisNftsOwned);
			tokenRelsLedger.set(fromRel, TOKEN_BALANCE, 0L);
			tokenRelsLedger.set(toRel, TOKEN_BALANCE, toThisNftsOwned + fromThisNftsOwned);

			hederaLedger.updateOwnershipChanges(nftId, from, to);

			return OK;
		});
	}

	private ResponseCodeEnum validateFeeSchedule(
			final List<CustomFee> feeSchedule,
			final TokenID targetTokenId,
			final MerkleToken targetToken
	) {
		if (feeSchedule.size() > properties.maxCustomFeesAllowed()) {
			return CUSTOM_FEES_LIST_TOO_LONG;
		}

		for (var customFee : feeSchedule) {
			final var feeCollector = customFee.getFeeCollectorAccountId();
			final var feeCollectorValidity = usableOrElse(feeCollector, INVALID_CUSTOM_FEE_COLLECTOR);

			if (feeCollectorValidity != OK) {
				return INVALID_CUSTOM_FEE_COLLECTOR;
			}
			ResponseCodeEnum responseCode;
			if (customFee.hasFixedFee()) {
				responseCode = validateFixed(customFee.getFixedFee(), feeCollector, targetTokenId);
				if (responseCode != OK) {
					return responseCode;
				}
			} else if (customFee.hasFractionalFee()) {
				responseCode = validateFractional(customFee, targetToken, targetTokenId, feeCollector);
				if (responseCode != OK) {
					return responseCode;
				}
			} else if (customFee.hasRoyaltyFee()) {
				responseCode = validateRoyalty(customFee, targetToken, targetTokenId, feeCollector);
				if (responseCode != OK) {
					return responseCode;
				}
			} else {
				return CUSTOM_FEE_NOT_FULLY_SPECIFIED;
			}
		}

		return OK;
	}

	private ResponseCodeEnum validateRoyalty(
			final CustomFee customFee,
			final MerkleToken targetToken,
			final TokenID targetTokenId,
			final AccountID feeCollector
	) {
		final var typeValidity = validateTypeConstraints(
				NON_FUNGIBLE_UNIQUE, targetToken, CUSTOM_ROYALTY_FEE_ONLY_ALLOWED_FOR_NON_FUNGIBLE_UNIQUE);
		if (typeValidity != OK) {
			return typeValidity;
		}

		final var royaltyFee = customFee.getRoyaltyFee();

		final var fraction = royaltyFee.getExchangeValueFraction();
		final var fractionalValidity = validateFeeFraction(fraction);
		if (fractionalValidity != OK) {
			return fractionalValidity;
		}
		if (fraction.getNumerator() > fraction.getDenominator()) {
			return ROYALTY_FRACTION_CANNOT_EXCEED_ONE;
		}

		if (royaltyFee.hasFallbackFee()) {
			final var fallback = royaltyFee.getFallbackFee();
			return validateFixed(fallback, feeCollector, targetTokenId);
		}

		return OK;
	}

	private ResponseCodeEnum validateFractional(
			final CustomFee customFee,
			final MerkleToken targetToken,
			final TokenID targetTokenId,
			final AccountID feeCollector
	) {
		final var typeValidity = validateTypeConstraints(
				FUNGIBLE_COMMON, targetToken, CUSTOM_FRACTIONAL_FEE_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON);
		if (typeValidity != OK) {
			return typeValidity;
		}
		if (!associationExists(feeCollector, targetTokenId)) {
			return TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
		}
		final var spec = customFee.getFractionalFee();
		final var fraction = spec.getFractionalAmount();
		final var fractionalValidity = validateFeeFraction(fraction);
		if (fractionalValidity != OK) {
			return fractionalValidity;
		}
		if (spec.getMaximumAmount() < 0 || spec.getMinimumAmount() < 0) {
			return CUSTOM_FEE_MUST_BE_POSITIVE;
		}
		if (spec.getMaximumAmount() > 0 && spec.getMaximumAmount() < spec.getMinimumAmount()) {
			return FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
		}
		return OK;
	}

	private ResponseCodeEnum validateTypeConstraints(
			final TokenType requiredType,
			final MerkleToken targetToken,
			final ResponseCodeEnum failureCode
	) {
		if (targetToken.tokenType() != requiredType) {
			return failureCode;
		}
		return OK;
	}

	private ResponseCodeEnum validateFeeFraction(final Fraction f) {
		if (f.getDenominator() == 0) {
			return FRACTION_DIVIDES_BY_ZERO;
		}
		if (!areValidPositiveNumbers(f.getNumerator(), f.getDenominator())) {
			return CUSTOM_FEE_MUST_BE_POSITIVE;
		}
		return OK;
	}

	private ResponseCodeEnum validateFixed(
			final FixedFee fixedFee,
			final AccountID feeCollector,
			final TokenID targetTokenId
	) {
		if (fixedFee.getAmount() <= 0) {
			return CUSTOM_FEE_MUST_BE_POSITIVE;
		}
		if (fixedFee.hasDenominatingTokenId()) {
			var denom = fixedFee.getDenominatingTokenId();
			if (0 == denom.getTokenNum()) {
				denom = targetTokenId;
			}
			if (resolve(denom) == MISSING_TOKEN) {
				return INVALID_TOKEN_ID_IN_CUSTOM_FEES;
			}
			final var merkleToken = get(denom);
			if (merkleToken.tokenType() == NON_FUNGIBLE_UNIQUE) {
				return CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
			}
			if (!pendingId.equals(denom) && !associationExists(feeCollector, denom)) {
				return TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
			}
		}
		return OK;
	}

	private boolean areValidPositiveNumbers(final long a, final long b) {
		return (a > 0 && b > 0);
	}

	@Override
	public void addKnownTreasury(final AccountID aId, final TokenID tId) {
		knownTreasuries.computeIfAbsent(aId, ignore -> new HashSet<>()).add(tId);
	}

	public void removeKnownTreasuryForToken(final AccountID aId, final TokenID tId) {
		throwIfKnownTreasuryIsMissing(aId);
		knownTreasuries.get(aId).remove(tId);
		if (knownTreasuries.get(aId).isEmpty()) {
			knownTreasuries.remove(aId);
		}
	}

	private void throwIfKnownTreasuryIsMissing(final AccountID aId) {
		if (!knownTreasuries.containsKey(aId)) {
			throw new IllegalArgumentException(String.format(
					"Argument 'aId=%s' does not refer to a known treasury!",
					readableId(aId)));
		}
	}

	private ResponseCodeEnum tryAdjustment(final AccountID aId, final TokenID tId, final long adjustment) {
		final var freezeAndKycValidity = checkRelFrozenAndKycProps(aId, tId);
		if (!freezeAndKycValidity.equals(OK)) {
			return freezeAndKycValidity;
		}

		final var relationship = asTokenRel(aId, tId);
		final var balance = (long) tokenRelsLedger.get(relationship, TOKEN_BALANCE);
		final var newBalance = balance + adjustment;
		if (newBalance < 0) {
			return INSUFFICIENT_TOKEN_BALANCE;
		}
		tokenRelsLedger.set(relationship, TOKEN_BALANCE, newBalance);
		hederaLedger.updateTokenXfers(tId, aId, adjustment);
		return OK;
	}

	private ResponseCodeEnum checkRelFrozenAndKycProps(final AccountID aId, final TokenID tId) {
		final var relationship = asTokenRel(aId, tId);
		if ((boolean) tokenRelsLedger.get(relationship, IS_FROZEN)) {
			return ACCOUNT_FROZEN_FOR_TOKEN;
		}
		if (!(boolean) tokenRelsLedger.get(relationship, IS_KYC_GRANTED)) {
			return ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
		}
		return OK;
	}

	private boolean isValidAutoRenewPeriod(final long secs) {
		return validator.isValidAutoRenewPeriod(Duration.newBuilder().setSeconds(secs).build());
	}

	@Override
	public void commitCreation() {
		throwIfNoCreationPending();

		tokens.get().put(fromTokenId(pendingId), pendingCreation);
		addKnownTreasury(pendingCreation.treasury().toGrpcAccountId(), pendingId);

		resetPendingCreation();
	}

	@Override
	public void rollbackCreation() {
		throwIfNoCreationPending();

		ids.reclaimLastId();
		resetPendingCreation();
	}

	@Override
	public ResponseCodeEnum update(final TokenUpdateTransactionBody changes, final long now) {
		final var tId = resolve(changes.getToken());
		if (tId == MISSING_TOKEN) {
			return INVALID_TOKEN_ID;
		}
		var validity = OK;
		final var isExpiryOnly = affectsExpiryAtMost(changes);

		validity = checkAutoRenewAccount(changes);
		if (validity != OK) {
			return validity;
		}

		final var newKycKey = changes.hasKycKey()
				? asUsableFcKey(changes.getKycKey()) : Optional.empty();
		final var newWipeKey = changes.hasWipeKey()
				? asUsableFcKey(changes.getWipeKey()) : Optional.empty();
		final var newSupplyKey = changes.hasSupplyKey()
				? asUsableFcKey(changes.getSupplyKey()) : Optional.empty();
		final var newFreezeKey = changes.hasFreezeKey()
				? asUsableFcKey(changes.getFreezeKey()) : Optional.empty();
		final var newFeeScheduleKey = changes.hasFeeScheduleKey()
				? asUsableFcKey(changes.getFeeScheduleKey()) : Optional.empty();

		var appliedValidity = new AtomicReference<>(OK);
		apply(tId, token -> {
			processExpiry(appliedValidity, changes, token);
			processAutoRenewAccount(appliedValidity, changes, token);

			checkKeyOfType(appliedValidity, token.hasKycKey(), newKycKey.isPresent(), TOKEN_HAS_NO_KYC_KEY);
			checkKeyOfType(appliedValidity, token.hasFreezeKey(), newFreezeKey.isPresent(), TOKEN_HAS_NO_FREEZE_KEY);
			checkKeyOfType(appliedValidity, token.hasWipeKey(), newWipeKey.isPresent(), TOKEN_HAS_NO_WIPE_KEY);
			checkKeyOfType(appliedValidity, token.hasSupplyKey(), newSupplyKey.isPresent(), TOKEN_HAS_NO_SUPPLY_KEY);
			checkKeyOfType(appliedValidity, token.hasAdminKey(), !isExpiryOnly, TOKEN_IS_IMMUTABLE);
			checkKeyOfType(appliedValidity, token.hasFeeScheduleKey(), newFeeScheduleKey.isPresent(),
					TOKEN_HAS_NO_FEE_SCHEDULE_KEY);
			if (OK != appliedValidity.get()) {
				return;
			}

			final var ret = checkNftBalances(token, tId, changes);
			if (ret != OK) {
				appliedValidity.set(ret);
				return;
			}

			updateAdminKeyIfAppropriate(token, changes);
			updateAutoRenewAccountIfAppropriate(token, changes);
			updateAutoRenewPeriodIfAppropriate(token, changes);

			updateKeyOfTypeIfAppropriate(changes.hasFreezeKey(), token::setFreezeKey, changes::getFreezeKey);
			updateKeyOfTypeIfAppropriate(changes.hasKycKey(), token::setKycKey, changes::getKycKey);
			updateKeyOfTypeIfAppropriate(changes.hasSupplyKey(), token::setSupplyKey, changes::getSupplyKey);
			updateKeyOfTypeIfAppropriate(changes.hasWipeKey(), token::setWipeKey, changes::getWipeKey);
			updateKeyOfTypeIfAppropriate(changes.hasFeeScheduleKey(), token::setFeeScheduleKey,
					changes::getFeeScheduleKey);

			updateTokenSymbolIfAppropriate(token, changes);
			updateTokenNameIfAppropriate(token, changes);
			updateTreasuryIfAppropriate(token, changes, tId);
			updateMemoIfAppropriate(token, changes);
			updateExpiryIfAppropriate(token, changes);
		});
		return appliedValidity.get();
	}

	private ResponseCodeEnum checkAutoRenewAccount(final TokenUpdateTransactionBody changes) {
		ResponseCodeEnum validity = OK;
		if (changes.hasAutoRenewAccount()) {
			validity = usableOrElse(changes.getAutoRenewAccount(), INVALID_AUTORENEW_ACCOUNT);
			if (validity != OK) {
				return validity;
			}
		}
		return validity;
	}

	private void processExpiry(
			final AtomicReference<ResponseCodeEnum> appliedValidity,
			final TokenUpdateTransactionBody changes,
			final MerkleToken token
	) {
		final var candidateExpiry = changes.getExpiry().getSeconds();
		if (candidateExpiry != 0 && candidateExpiry < token.expiry()) {
			appliedValidity.set(INVALID_EXPIRATION_TIME);
		}
	}

	private void processAutoRenewAccount(
			final AtomicReference<ResponseCodeEnum> appliedValidity,
			final TokenUpdateTransactionBody changes,
			final MerkleToken token
	) {
		if (changes.hasAutoRenewAccount() || token.hasAutoRenewAccount()) {
			final long changedAutoRenewPeriod = changes.getAutoRenewPeriod().getSeconds();
			if ((changedAutoRenewPeriod != 0 || !token.hasAutoRenewAccount()) &&
					!isValidAutoRenewPeriod(changedAutoRenewPeriod)) {
				appliedValidity.set(INVALID_RENEWAL_PERIOD);
			}
		}
	}

	private void checkKeyOfType(
			final AtomicReference<ResponseCodeEnum> appliedValidity,
			final boolean hasKey,
			final boolean keyPresentOrExpiryOnly,
			final ResponseCodeEnum code
	) {
		if (!hasKey && keyPresentOrExpiryOnly) {
			appliedValidity.set(code);
		}
	}

	private ResponseCodeEnum checkNftBalances(
			final MerkleToken token,
			final TokenID tId,
			final TokenUpdateTransactionBody changes
	) {
		if (token.tokenType().equals(TokenType.NON_FUNGIBLE_UNIQUE)) {
			if (changes.hasTreasury()) {
				/* This relationship is verified to exist in the TokenUpdateTransitionLogic */
				final var newTreasuryRel = asTokenRel(changes.getTreasury(), tId);
				final var balance = (long) tokenRelsLedger.get(newTreasuryRel, TOKEN_BALANCE);
				if (balance != 0) {
					return TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
				}
			}
		}
		return OK;
	}

	private void updateAdminKeyIfAppropriate(final MerkleToken token, final TokenUpdateTransactionBody changes) {
		if (changes.hasAdminKey()) {
			final var newAdminKey = changes.getAdminKey();
			if (REMOVES_ADMIN_KEY.test(newAdminKey)) {
				token.setAdminKey(UNUSED_KEY);
			} else {
				token.setAdminKey(asFcKeyUnchecked(newAdminKey));
			}
		}
	}

	private void updateAutoRenewAccountIfAppropriate(final MerkleToken token,
			final TokenUpdateTransactionBody changes) {
		if (changes.hasAutoRenewAccount()) {
			token.setAutoRenewAccount(fromGrpcAccountId(changes.getAutoRenewAccount()));
		}
	}

	private void updateAutoRenewPeriodIfAppropriate(final MerkleToken token, final TokenUpdateTransactionBody changes) {
		if (token.hasAutoRenewAccount()) {
			final long changedAutoRenewPeriod = changes.getAutoRenewPeriod().getSeconds();
			if (changedAutoRenewPeriod > 0) {
				token.setAutoRenewPeriod(changedAutoRenewPeriod);
			}
		}
	}

	private void updateTokenSymbolIfAppropriate(final MerkleToken token, final TokenUpdateTransactionBody changes) {
		if (changes.getSymbol().length() > 0) {
			token.setSymbol(changes.getSymbol());
		}
	}

	private void updateTokenNameIfAppropriate(final MerkleToken token, final TokenUpdateTransactionBody changes) {
		if (changes.getName().length() > 0) {
			token.setName(changes.getName());
		}
	}

	private void updateMemoIfAppropriate(final MerkleToken token, final TokenUpdateTransactionBody changes) {
		if (changes.hasMemo()) {
			token.setMemo(changes.getMemo().getValue());
		}
	}

	private void updateExpiryIfAppropriate(final MerkleToken token, final TokenUpdateTransactionBody changes) {
		final var expiry = changes.getExpiry().getSeconds();
		if (expiry != 0) {
			token.setExpiry(expiry);
		}
	}

	private void updateTreasuryIfAppropriate(final MerkleToken token,
			final TokenUpdateTransactionBody changes,
			final TokenID tId) {
		if (changes.hasTreasury() && !changes.getTreasury().equals(token.treasury().toGrpcAccountId())) {
			final var treasuryId = fromGrpcAccountId(changes.getTreasury());
			removeKnownTreasuryForToken(token.treasury().toGrpcAccountId(), tId);
			token.setTreasury(treasuryId);
			addKnownTreasury(changes.getTreasury(), tId);
		}
	}

	private void updateKeyOfTypeIfAppropriate(
			final boolean check,
			final Consumer<JKey> consumer, Supplier<Key> supplier
	) {
		if (check) {
			consumer.accept(asFcKeyUnchecked(supplier.get()));
		}
	}

	@Override
	public ResponseCodeEnum updateFeeSchedule(final TokenFeeScheduleUpdateTransactionBody changes) {
		final var tId = resolve(changes.getTokenId());
		if (tId == MISSING_TOKEN) {
			return INVALID_TOKEN_ID;
		}

		final var token = get(tId);
		if (!token.hasFeeScheduleKey()) {
			return TOKEN_HAS_NO_FEE_SCHEDULE_KEY;
		}
		final var customFees = changes.getCustomFeesList();
		if (customFees.isEmpty() && token.grpcFeeSchedule().isEmpty()) {
			return CUSTOM_SCHEDULE_ALREADY_HAS_NO_FEES;
		}

		final var validity = validateFeeSchedule(changes.getCustomFeesList(), tId, token);
		if (validity != OK) {
			return validity;
		}
		token.setFeeScheduleFrom(customFees, EntityId.fromGrpcTokenId(tId));

		return OK;
	}

	public static boolean affectsExpiryAtMost(final TokenUpdateTransactionBody op) {
		return !op.hasAdminKey() &&
				!op.hasKycKey() &&
				!op.hasWipeKey() &&
				!op.hasFreezeKey() &&
				!op.hasSupplyKey() &&
				!op.hasFeeScheduleKey() &&
				!op.hasTreasury() &&
				!op.hasAutoRenewAccount() &&
				op.getSymbol().length() == 0 &&
				op.getName().length() == 0 &&
				op.getAutoRenewPeriod().getSeconds() == 0;
	}

	private ResponseCodeEnum fullySanityChecked(
			final boolean strictTokenCheck,
			final AccountID aId,
			final List<TokenID> tokens,
			final BiFunction<AccountID, List<TokenID>, ResponseCodeEnum> action
	) {
		final var validity = checkAccountUsability(aId);
		if (validity != OK) {
			return validity;
		}
		if (strictTokenCheck) {
			for (var tID : tokens) {
				final var id = resolve(tID);
				if (id == MISSING_TOKEN) {
					return INVALID_TOKEN_ID;
				}
				final var token = get(id);
				if (token.isDeleted()) {
					return TOKEN_WAS_DELETED;
				}
			}
		}
		return action.apply(aId, tokens);
	}

	private void resetPendingCreation() {
		pendingId = NO_PENDING_ID;
		pendingCreation = null;
	}

	private void throwIfNoCreationPending() {
		if (pendingId == NO_PENDING_ID) {
			throw new IllegalStateException("No pending token creation!");
		}
	}

	private void throwIfMissing(final TokenID id) {
		if (!exists(id)) {
			throw new IllegalArgumentException(String.format(
					"Argument 'id=%s' does not refer to a known token!",
					readableId(id)));
		}
	}

	public boolean isKnownTreasury(final AccountID aid) {
		return knownTreasuries.containsKey(aid);
	}

	@Override
	public boolean isTreasuryForToken(final AccountID aId, final TokenID tId) {
		if (!knownTreasuries.containsKey(aId)) {
			return false;
		}
		return knownTreasuries.get(aId).contains(tId);
	}

	private ResponseCodeEnum manageFlag(
			final AccountID aId,
			final TokenID tId,
			final boolean value,
			final ResponseCodeEnum keyFailure,
			final TokenRelProperty flagProperty,
			final Function<MerkleToken, Optional<JKey>> controlKeyFn
	) {
		return sanityChecked(false, aId, null, tId, token -> {
			if (controlKeyFn.apply(token).isEmpty()) {
				return keyFailure;
			}
			final var relationship = asTokenRel(aId, tId);
			tokenRelsLedger.set(relationship, flagProperty, value);
			return OK;
		});
	}

	private ResponseCodeEnum sanityCheckedFungibleCommon(
			final AccountID aId,
			final TokenID tId,
			final Function<MerkleToken, ResponseCodeEnum> action
	) {
		return sanityChecked(true, aId, null, tId, action);
	}

	private ResponseCodeEnum sanityChecked(
			final boolean onlyFungibleCommon,
			final AccountID aId,
			final AccountID aCounterPartyId,
			final TokenID tId,
			final Function<MerkleToken, ResponseCodeEnum> action
	) {
		var validity = checkAccountUsability(aId);
		if (validity != OK) {
			return validity;
		}
		if (aCounterPartyId != null) {
			validity = checkAccountUsability(aCounterPartyId);
			if (validity != OK) {
				return validity;
			}
		}

		validity = checkTokenExistence(tId);
		if (validity != OK) {
			return validity;
		}

		final var token = get(tId);
		if (token.isDeleted()) {
			return TOKEN_WAS_DELETED;
		}
		if (onlyFungibleCommon && token.tokenType() == NON_FUNGIBLE_UNIQUE) {
			return ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
		}

		var key = asTokenRel(aId, tId);
		/*
		 * Instead of returning  TOKEN_NOT_ASSOCIATED_TO_ACCOUNT when a token is not associated,
		 * we check if the account has any maxAutoAssociations set up, if they do check if we reached the limit and
		 * auto associate. If not return EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT
		 */
		if (!tokenRelsLedger.exists(key)) {
			validity = validateAndAutoAssociate(aId, tId);
			if (validity != OK) {
				return validity;
			}
		}
		if (aCounterPartyId != null) {
			key = asTokenRel(aCounterPartyId, tId);
			if (!tokenRelsLedger.exists(key)) {
				validity = validateAndAutoAssociate(aId, tId);
				if (validity != OK) {
					return validity;
				}
			}
		}

		return action.apply(token);
	}

	private ResponseCodeEnum validateAndAutoAssociate(AccountID aId, TokenID tId) {
		if (hederaLedger.maxAutomaticAssociations(aId) > 0) {
			return associate(aId, List.of(tId), true);
		}
		return TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
	}

	private ResponseCodeEnum checkExistence(final AccountID aId, final TokenID tId) {
		final var validity = checkAccountUsability(aId);
		if (validity != OK) {
			return validity;
		}
		return exists(tId) ? OK : INVALID_TOKEN_ID;
	}

	private ResponseCodeEnum checkTokenExistence(final TokenID tId) {
		return exists(tId) ? OK : INVALID_TOKEN_ID;
	}

	Map<AccountID, Set<TokenID>> getKnownTreasuries() {
		return knownTreasuries;
	}
}
