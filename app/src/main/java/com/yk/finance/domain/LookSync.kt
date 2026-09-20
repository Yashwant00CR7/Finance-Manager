package com.yk.finance.domain

import com.yk.finance.data.FinanceDao

/**
 * Fills in the icon and colour of anything that has none.
 *
 * Runs on every start like [CategorySync], and for the same reason: these are values
 * you can edit, so the seed has to converge rather than fire once. Only nulls are
 * touched - a colour you picked is never overwritten by the default for its name.
 */
object LookSync {

    suspend fun run(dao: FinanceDao) {
        dao.allCategories().forEach { category ->
            if (category.iconKey != null && category.colourHex != null) return@forEach
            dao.updateCategory(
                category.copy(
                    iconKey = category.iconKey ?: Looks.categoryIcon(category.name),
                    colourHex = category.colourHex ?: Looks.categoryColour(category.name),
                ),
            )
        }

        dao.allAccounts().forEach { account ->
            if (account.iconKey != null && account.colourHex != null) return@forEach
            dao.updateAccount(
                account.copy(
                    iconKey = account.iconKey
                        ?: Looks.accountIcon(account.displayName, account.kind),
                    colourHex = account.colourHex
                        ?: Looks.accountColour(account.displayName, account.kind),
                ),
            )
        }
    }
}
