package com.hedera.services.fees.calculation.contract.queries;

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
import com.hedera.services.fees.calculation.QueryResourceUsageEstimator;
import com.hedera.services.usage.contract.ContractGetInfoUsage;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.hedera.services.queries.AnswerService.NO_QUERY_CTX;
import static com.hedera.services.queries.contract.GetContractInfoAnswer.CONTRACT_INFO_CTX_KEY;

@Singleton
public class GetContractInfoResourceUsage implements QueryResourceUsageEstimator {
	static Function<Query, ContractGetInfoUsage> factory = ContractGetInfoUsage::newEstimate;

	@Inject
	public GetContractInfoResourceUsage() {
	}

	@Override
	public boolean applicableTo(Query query) {
		return query.hasContractGetInfo();
	}

	@Override
	public FeeData usageGiven(Query query, StateView view) {
		return usageFor(query, view, query.getContractGetInfo().getHeader().getResponseType(), NO_QUERY_CTX);
	}

	@Override
	public FeeData usageGivenType(Query query, StateView view, ResponseType type) {
		return usageFor(query, view, type, NO_QUERY_CTX);
	}

	@Override
	public FeeData usageGiven(Query query, StateView view, Map<String, Object> queryCtx) {
		return usageFor(
				query,
				view,
				query.getContractGetInfo().getHeader().getResponseType(),
				Optional.of(queryCtx));
	}

	private FeeData usageFor(Query query, StateView view, ResponseType type, Optional<Map<String, Object>> queryCtx) {
		var op = query.getContractGetInfo();
		var tentativeInfo = view.infoForContract(op.getContractID());
		if (tentativeInfo.isPresent()) {
			var info = tentativeInfo.get();
			queryCtx.ifPresent(ctx -> ctx.put(CONTRACT_INFO_CTX_KEY, info));
			var estimate = factory.apply(query)
					.givenCurrentKey(info.getAdminKey())
					.givenCurrentMemo(info.getMemo())
					.givenCurrentTokenAssocs(info.getTokenRelationshipsCount());
			return estimate.get();
		} else {
			return FeeData.getDefaultInstance();
		}
	}
}
