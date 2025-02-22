package com.hedera.test.factories.scenarios;

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

import com.hedera.services.utils.PlatformTxnAccessor;

import static com.hedera.test.factories.txns.CryptoDeleteFactory.newSignedCryptoDelete;
import static com.hedera.test.factories.txns.PlatformTxnFactory.from;

public enum CryptoDeleteScenarios implements TxnHandlingScenario {
	CRYPTO_DELETE_NO_TARGET_RECEIVER_SIG_SCENARIO {
		public PlatformTxnAccessor platformTxn() throws Throwable {
			return new PlatformTxnAccessor(from(
					newSignedCryptoDelete(MISC_ACCOUNT_ID, NO_RECEIVER_SIG_ID).get()
			));
		}
	},
	CRYPTO_DELETE_TARGET_RECEIVER_SIG_SCENARIO {
		public PlatformTxnAccessor platformTxn() throws Throwable {
			return new PlatformTxnAccessor(from(
					newSignedCryptoDelete(MISC_ACCOUNT_ID, RECEIVER_SIG_ID).get()
			));
		}
	},
	CRYPTO_DELETE_MISSING_RECEIVER_SIG_SCENARIO {
		public PlatformTxnAccessor platformTxn() throws Throwable {
			return new PlatformTxnAccessor(from(
					newSignedCryptoDelete(MISC_ACCOUNT_ID, MISSING_ACCOUNT_ID).get()
			));
		}
	},
	CRYPTO_DELETE_MISSING_TARGET {
		public PlatformTxnAccessor platformTxn() throws Throwable {
			return new PlatformTxnAccessor(from(
					newSignedCryptoDelete(MISSING_ACCOUNT_ID, NO_RECEIVER_SIG_ID).get()
			));
		}
	},
}
