package com.hedera.services.txns.file;

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

import com.hedera.services.fees.annotations.FunctionKey;
import com.hedera.services.txns.TransitionLogic;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;

import java.util.List;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileUpdate;

@Module
public abstract class FileLogicModule {
	@Provides
	@IntoMap
	@FunctionKey(FileUpdate)
	public static List<TransitionLogic> provideFileUpdateLogic(FileUpdateTransitionLogic fileUpdateTransitionLogic) {
		return List.of(fileUpdateTransitionLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(FileCreate)
	public static List<TransitionLogic> provideFileCreateLogic(FileCreateTransitionLogic fileCreateTransitionLogic) {
		return List.of(fileCreateTransitionLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(FileDelete)
	public static List<TransitionLogic> provideFileDeleteLogic(FileDeleteTransitionLogic fileDeleteTransitionLogic) {
		return List.of(fileDeleteTransitionLogic);
	}

	@Provides
	@IntoMap
	@FunctionKey(FileAppend)
	public static List<TransitionLogic> provideFileAppendLogic(FileAppendTransitionLogic fileAppendTransitionLogic) {
		return List.of(fileAppendTransitionLogic);
	}
}
