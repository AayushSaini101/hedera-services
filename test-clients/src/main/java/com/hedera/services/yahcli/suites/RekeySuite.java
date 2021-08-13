package com.hedera.services.yahcli.suites;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;

public class RekeySuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(RekeySuite.class);

	private final String account;
	private final Map<String, String> specConfig;

	public RekeySuite(final Map<String, String> specConfig, final String account) {
		this.specConfig = specConfig;
		this.account = Utils.getAccount(account);
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(rekey());
	}

	private HapiApiSpec rekey() {
		return HapiApiSpec.customHapiSpec("rekey" + account)
				.withProperties(specConfig)
				.given().when()
				.then(
						cryptoUpdate(account)
								.noLogging()
								.yahcliLogging()
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
