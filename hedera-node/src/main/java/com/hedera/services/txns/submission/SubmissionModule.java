package com.hedera.services.txns.submission;

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

import com.hedera.services.txns.submission.annotations.MaxProtoMsgDepth;
import com.hedera.services.txns.submission.annotations.MaxSignedTxnSize;
import com.swirlds.common.Platform;
import dagger.Module;
import dagger.Provides;

import static com.hedera.services.txns.submission.StructuralPrecheck.HISTORICAL_MAX_PROTO_MESSAGE_DEPTH;

@Module
public class SubmissionModule {
	@Provides
	@MaxSignedTxnSize
	public static int provideMaxSignedTxnSize() {
		return Platform.getTransactionMaxBytes();
	}

	@Provides
	@MaxProtoMsgDepth
	public static int provideMaxProtoMsgDepth() {
		return HISTORICAL_MAX_PROTO_MESSAGE_DEPTH;
	}
}
