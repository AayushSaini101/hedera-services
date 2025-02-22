package com.hedera.services.txns.network;

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

import com.hedera.services.legacy.handler.FreezeHandler;
import com.swirlds.common.NodeId;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateHelper {
	private final NodeId nodeId;
	private final FreezeHandler delegate;

	@Inject
	public UpdateHelper(NodeId nodeId, FreezeHandler delegate) {
		this.nodeId = nodeId;
		this.delegate = delegate;
	}

	public void runIfAppropriateOn(String os) {
		if (nodeId.getId() == 0 || !os.contains("mac")) {
			delegate.handleUpdateFeature();
		}
	}
}
