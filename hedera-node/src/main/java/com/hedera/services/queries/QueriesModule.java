package com.hedera.services.queries;

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

import com.hedera.services.context.ServicesNodeType;
import com.hedera.services.context.domain.security.HapiOpPermissions;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.legacy.handler.SmartContractRequestHandler;
import com.hedera.services.queries.answering.QueryHeaderValidity;
import com.hedera.services.queries.answering.StakedAnswerFlow;
import com.hedera.services.queries.answering.ZeroStakeAnswerFlow;
import com.hedera.services.queries.contract.ContractCallLocalAnswer;
import com.hedera.services.queries.validation.QueryFeeCheck;
import com.hedera.services.throttling.FunctionalityThrottling;
import com.hedera.services.throttling.annotations.HapiThrottle;
import com.hedera.services.txns.submission.PlatformSubmissionManager;
import com.hedera.services.txns.submission.TransactionPrecheck;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;
import java.util.function.Supplier;

import static com.hedera.services.context.ServicesNodeType.STAKED_NODE;

@Module
public abstract class QueriesModule {
	@Provides
	@Singleton
	public static ContractCallLocalAnswer.LegacyLocalCaller provideLocalCall(SmartContractRequestHandler contracts) {
		return contracts::contractCallLocal;
	}

	@Provides
	@Singleton
	public static AnswerFlow provideAnswerFlow(
			FeeCalculator fees,
			QueryFeeCheck queryFeeCheck,
			ServicesNodeType nodeType,
			HapiOpPermissions hapiOpPermissions,
			Supplier<StateView> stateViews,
			UsagePricesProvider usagePrices,
			QueryHeaderValidity queryHeaderValidity,
			TransactionPrecheck transactionPrecheck,
			PlatformSubmissionManager submissionManager,
			@HapiThrottle FunctionalityThrottling hapiThrottling
	) {
		if (nodeType == STAKED_NODE) {
			return new StakedAnswerFlow(
					fees,
					stateViews,
					usagePrices,
					hapiThrottling,
					submissionManager,
					queryHeaderValidity,
					transactionPrecheck,
					hapiOpPermissions,
					queryFeeCheck);
		} else {
			return new ZeroStakeAnswerFlow(queryHeaderValidity, stateViews, hapiThrottling);
		}
	}
}
