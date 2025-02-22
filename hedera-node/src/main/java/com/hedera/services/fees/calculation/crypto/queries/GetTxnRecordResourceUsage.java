package com.hedera.services.fees.calculation.crypto.queries;

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
import com.hedera.services.fees.calculation.FeeCalcUtils;
import com.hedera.services.fees.calculation.QueryResourceUsageEstimator;
import com.hedera.services.queries.answering.AnswerFunctions;
import com.hedera.services.records.RecordCache;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.fee.CryptoFeeBuilder;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;

import static com.hedera.services.queries.AnswerService.NO_QUERY_CTX;
import static com.hedera.services.queries.meta.GetTxnRecordAnswer.DUPLICATE_RECORDS_CTX_KEY;
import static com.hedera.services.queries.meta.GetTxnRecordAnswer.PRIORITY_RECORD_CTX_KEY;

@Singleton
public class GetTxnRecordResourceUsage implements QueryResourceUsageEstimator {
	static final TransactionRecord MISSING_RECORD_STANDIN = TransactionRecord.getDefaultInstance();

	private final RecordCache recordCache;
	private final AnswerFunctions answerFunctions;
	private final CryptoFeeBuilder usageEstimator;

	static BinaryOperator<FeeData> sumFn = FeeCalcUtils::sumOfUsages;

	@Inject
	public GetTxnRecordResourceUsage(
			final RecordCache recordCache,
			final AnswerFunctions answerFunctions,
			final CryptoFeeBuilder usageEstimator
	) {
		this.recordCache = recordCache;
		this.usageEstimator = usageEstimator;
		this.answerFunctions = answerFunctions;
	}

	@Override
	public boolean applicableTo(final Query query) {
		return query.hasTransactionGetRecord();
	}

	@Override
	public FeeData usageGiven(final Query query, final StateView view) {
		return usageFor(query, view, query.getTransactionGetRecord().getHeader().getResponseType(), NO_QUERY_CTX);
	}

	@Override
	public FeeData usageGiven(final Query query, final StateView view, final Map<String, Object> queryCtx) {
		return usageFor(
				query,
				view,
				query.getTransactionGetRecord().getHeader().getResponseType(),
				Optional.of(queryCtx));
	}

	@Override
	public FeeData usageGivenType(final Query query, final StateView view, final ResponseType type) {
		return usageFor(query, view, type, NO_QUERY_CTX);
	}

	private FeeData usageFor(
			final Query query,
			final StateView view,
			final ResponseType type,
			final Optional<Map<String, Object>> queryCtx
	) {
		final var op = query.getTransactionGetRecord();
		final var txnRecord = answerFunctions.txnRecord(recordCache, view, op).orElse(MISSING_RECORD_STANDIN);
		var usages = usageEstimator.getTransactionRecordQueryFeeMatrices(txnRecord, type);
		if (txnRecord != MISSING_RECORD_STANDIN) {
			queryCtx.ifPresent(ctx -> ctx.put(PRIORITY_RECORD_CTX_KEY, txnRecord));
			if (op.getIncludeDuplicates()) {
				final var duplicateRecords = recordCache.getDuplicateRecords(op.getTransactionID());
				queryCtx.ifPresent(ctx -> ctx.put(DUPLICATE_RECORDS_CTX_KEY, duplicateRecords));
				for (final var duplicate : duplicateRecords) {
					final var duplicateUsage = usageEstimator.getTransactionRecordQueryFeeMatrices(duplicate, type);
					usages = sumFn.apply(usages, duplicateUsage);
				}
			}
		}
		return usages;
	}
}
