package com.hedera.services.files.sysfiles;

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

import com.hedera.services.context.domain.security.HapiOpPermissions;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.PropertySources;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.Consumer;

@Singleton
public class ConfigCallbacks {
	private final PropertySources propertySources;
	private final HapiOpPermissions hapiOpPermissions;
	private final GlobalDynamicProperties dynamicProps;

	@Inject
	public ConfigCallbacks(
			HapiOpPermissions hapiOpPermissions,
			GlobalDynamicProperties dynamicProps,
			PropertySources propertySources
	) {
		this.dynamicProps = dynamicProps;
		this.propertySources = propertySources;
		this.hapiOpPermissions = hapiOpPermissions;
	}

	public Consumer<ServicesConfigurationList> propertiesCb() {
		return config -> {
			propertySources.reloadFrom(config);
			dynamicProps.reload();
		};
	}

	public Consumer<ServicesConfigurationList> permissionsCb() {
		return hapiOpPermissions::reloadFrom;
	}
}
