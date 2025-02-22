package com.hedera.services.txns.token;

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
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.txns.TransitionLogic;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hedera.services.store.tokens.TokenStore.MISSING_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

@Singleton
public class TokenFeeScheduleUpdateTransitionLogic implements TransitionLogic {
	private static final Logger log = LogManager.getLogger(TokenFeeScheduleUpdateTransitionLogic.class);
	private final TokenStore store;
	private final TransactionContext txnCtx;

	private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validate;

	@Inject
	public TokenFeeScheduleUpdateTransitionLogic(final TokenStore tokenStore, final TransactionContext txnCtx) {
		this.store = tokenStore;
		this.txnCtx = txnCtx;
	}

	@Override
	public void doStateTransition() {
		try {
			transitionFor(txnCtx.accessor().getTxn().getTokenFeeScheduleUpdate());
		} catch (Exception e) {
			log.warn("Unhandled error while processing :: {}!", txnCtx.accessor().getSignedTxnWrapper(), e);
			abortWith(FAIL_INVALID);
		}

	}

	private void transitionFor(TokenFeeScheduleUpdateTransactionBody op) {
		final var id = store.resolve(op.getTokenId());
		if (id == MISSING_TOKEN) {
			txnCtx.setStatus(INVALID_TOKEN_ID);
			return;
		}

		final var token = store.get(id);
		if (token.isDeleted()) {
			txnCtx.setStatus(TOKEN_WAS_DELETED);
			return;
		}

		final var outcome = store.updateFeeSchedule(op);
		if (outcome != OK) {
			abortWith(outcome);
			return;
		}
		txnCtx.setStatus(SUCCESS);
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasTokenFeeScheduleUpdate;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
		return SEMANTIC_CHECK;
	}

	private ResponseCodeEnum validate(TransactionBody txnBody) {
		final var op = txnBody.getTokenFeeScheduleUpdate();
		if (!op.hasTokenId()) {
			return INVALID_TOKEN_ID;
		}

		return OK;
	}

	private void abortWith(ResponseCodeEnum cause) {
		txnCtx.setStatus(cause);
	}
}
