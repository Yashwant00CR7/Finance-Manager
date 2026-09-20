package com.yk.finance.domain

import com.yk.finance.data.Category
import com.yk.finance.data.FinanceDao

/**
 * Brings the category table in line with [SEED_CATEGORIES] on every start.
 *
 * Runs unconditionally rather than only on an empty database, because the v1 names
 * are already in use and renaming them in place is the only way a transaction filed
 * under "Food & Drink" keeps its category without being touched by hand. Idempotent
 * by construction: every step checks for the end state before acting, so a hundred
 * launches do the same thing as one.
 *
 * Deliberately not a Room migration. Room migrations run once and cannot be corrected
 * afterwards; this is data the user can edit, so it has to converge rather than fire.
 */
object CategorySync {

    suspend fun run(dao: FinanceDao) {
        applyRenames(dao)
        seedMissing(dao)
    }

    /**
     * The name this app would use for a category another app calls [name].
     *
     * The importer asks before creating anything, so a file full of "Groceries" files
     * its rows under Food rather than creating a category the next launch would rename
     * away underneath them.
     */
    fun canonical(name: String): String {
        val trimmed = name.trim()
        return CATEGORY_RENAMES[trimmed] ?: trimmed
    }

    /** Finds or creates a category by name, canonicalising first. */
    suspend fun resolve(dao: FinanceDao, name: String, isIncome: Boolean): Category {
        val wanted = canonical(name)
        dao.categoryByName(wanted)?.let { return it }
        val created = Category(name = wanted, isIncome = isIncome)
        return created.copy(id = dao.insertCategory(created))
    }

    private suspend fun applyRenames(dao: FinanceDao) {
        CATEGORY_RENAMES.forEach { (oldName, newName) ->
            val from = dao.categoryByName(oldName) ?: return@forEach
            val to = dao.categoryByName(newName)
            when {
                to == null -> dao.updateCategory(from.copy(name = newName))
                to.id != from.id -> merge(dao, from, to)
            }
        }
    }

    /**
     * Everything pointing at the losing category has to move before it is deleted -
     * otherwise transactions and learned rules are left holding an id that resolves to
     * nothing, which reads on screen as spending that lost its label for no reason.
     */
    private suspend fun merge(dao: FinanceDao, from: Category, to: Category) {
        dao.moveTransactionsToCategory(from.id, to.id)
        dao.moveRulesToCategory(from.id, to.id)
        // Two budgets on one category would read as a doubled cap, so the survivor's
        // limit wins and the loser's is dropped rather than added.
        if (dao.allBudgets().any { it.categoryId == to.id }) {
            dao.deleteBudgetsForCategory(from.id)
        } else {
            dao.moveBudgetsToCategory(from.id, to.id)
        }
        dao.deleteCategory(from.id)
    }

    private suspend fun seedMissing(dao: FinanceDao) {
        SEED_CATEGORIES.forEach { seed ->
            val existing = dao.categoryByName(seed.name)
            when {
                existing == null -> dao.insertCategory(
                    Category(name = seed.name, isIncome = seed.isIncome, sharing = seed.sharing),
                )
                // Converges both flags in one write. A database migrated from v3 has
                // every category at sharing = NONE, so this is what actually teaches
                // the two "somebody else's money" categories what they are.
                existing.isIncome != seed.isIncome || existing.sharing != seed.sharing ->
                    dao.updateCategory(
                        existing.copy(isIncome = seed.isIncome, sharing = seed.sharing),
                    )
            }
        }
    }
}
