package com.hedera.services.fees.calculation.meta.queries;

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
import com.hedera.services.fees.calculation.meta.FixedUsageEstimates;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseType;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class GetVersionInfoResourceUsage implements QueryResourceUsageEstimator {
	@Inject
	public GetVersionInfoResourceUsage() {
	}

	@Override
	public boolean applicableTo(Query query) {
		return query.hasNetworkGetVersionInfo();
	}

	@Override
	public FeeData usageGiven(Query query, StateView view) {
		return FixedUsageEstimates.getVersionInfoUsage();
	}

	@Override
	public FeeData usageGivenType(Query query, StateView view, ResponseType type) {
		return FixedUsageEstimates.getVersionInfoUsage();
	}
}
