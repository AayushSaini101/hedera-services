package com.hedera.services.yahcli.commands.accounts;

import com.hedera.services.yahcli.config.ConfigManager;
import picocli.CommandLine;

import java.util.concurrent.Callable;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

@CommandLine.Command(
		name = "rekey",
		subcommands = { CommandLine.HelpCommand.class },
		description = "Retrieve the balance of account(s) on the target network")
public class RekeyCommand implements Callable<Integer> {
	@CommandLine.ParentCommand
	AccountsCommand accountsCommand;

	@CommandLine.Parameters(
			arity = "1",
			paramLabel = "<account>",
			description = "number of account to rekey")
	String account;

	@Override
	public Integer call() throws Exception {
		var config = ConfigManager.from(accountsCommand.getYahcli());
		config.assertNoMissingDefaults();
		COMMON_MESSAGES.printGlobalInfo(config);

//		var delegate = new BalanceSuite(config.asSpecConfig(), );
//		delegate.runSuiteSync();

		return 0;
	}
}
