package com.hedera.services.state.logic;

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

import com.hedera.services.ServicesState;
import com.hedera.services.stream.RecordStreamManager;
import com.swirlds.common.notification.listeners.ReconnectCompleteListener;
import com.swirlds.common.notification.listeners.ReconnectCompleteNotification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ReconnectListener implements ReconnectCompleteListener {
	private static final Logger log = LogManager.getLogger(ReconnectListener.class);

	private final RecordStreamManager recordStreamManager;

	@Inject
	public ReconnectListener(RecordStreamManager recordStreamManager) {
		this.recordStreamManager = recordStreamManager;
	}

	@Override
	public void notify(ReconnectCompleteNotification notification) {
		log.info(
				"Notification Received: Reconnect Finished. " +
						"consensusTimestamp: {}, roundNumber: {}, sequence: {}",
				notification.getConsensusTimestamp(),
				notification.getRoundNumber(),
				notification.getSequence());
		ServicesState state = (ServicesState) notification.getState();
		state.logSummary();
		recordStreamManager.setStartWriteAtCompleteWindow(true);
	}
}
