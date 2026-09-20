package com.yk.finance.importer

import com.yk.finance.data.Account
import com.yk.finance.data.AccountKind

/**
 * Resolving a name in someone else's export to an account here.
 *
 * Aliases are stored on the account rather than in a lookup table so that `Salary`
 * resolves to the ICICI account forever, not just for the run you happened to correct.
 */
object AccountBinder {

    fun aliasesOf(account: Account): List<String> =
        account.aliases?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun bindingFor(accounts: List<Account>, name: String): AccountBinding {
        val wanted = name.trim()
        if (wanted.isEmpty()) return AccountBinding.Unbound
        val account = accounts.firstOrNull { account ->
            account.displayName.equals(wanted, ignoreCase = true) ||
                aliasesOf(account).any { it.equals(wanted, ignoreCase = true) }
        } ?: return AccountBinding.Unbound
        return if (account.kind == AccountKind.CASH) {
            AccountBinding.Cash(account.id)
        } else {
            AccountBinding.Bank(account.id)
        }
    }

    fun accountFor(accounts: List<Account>, name: String): Account? {
        val wanted = name.trim()
        return accounts.firstOrNull { account ->
            account.displayName.equals(wanted, ignoreCase = true) ||
                aliasesOf(account).any { it.equals(wanted, ignoreCase = true) }
        }
    }
}

/**
 * The result of a rehearsal.
 *
 * Stages 1-5 with the committer switched off, which is what dissolves the
 * no-rehearsal problem: the pipeline that will eventually write can be run against
 * real exports as often as you like, and it writes nothing.
 */
sealed interface DryRunResult {
    data class Refused(val reason: String) : DryRunResult

    data class Ready(
        val plan: ImportPlan,
        val diff: DiffReport,
        /** Account names in the file, paired with what they currently resolve to. */
        val bindings: List<Binding>,
        /**
         * Exactly what a commit would do with this file, worked out now.
         *
         * The rehearsal shows the real decisions rather than a summary of them, which is
         * what makes agreeing to an import an informed act instead of a leap.
         */
        val commit: CommitPlan,
    ) : DryRunResult

    data class Binding(val name: String, val boundTo: Account?)
}
