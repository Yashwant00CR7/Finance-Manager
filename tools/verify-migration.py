#!/usr/bin/env python3
"""
Checks a hand-written Room migration against the schema Room will validate against.

v1 shipped with exportSchema = false, so there is no 1.json and Room's own
MigrationTestHelper cannot generate the "before" database. This compares the SQL in
MIGRATION_1_2 with the createSql Room recorded in the exported schema instead, which
catches the failure that matters: a migration producing a table Room then rejects at
open time, on a phone, with a ledger inside it.

    python3 tools/verify-migration.py            # exits non-zero on mismatch
"""
import json
import re
import sys

SCHEMA = "app/schemas/com.yk.finance.data.AppDatabase/2.json"
SOURCE = "app/src/main/java/com/yk/finance/data/AppDatabase.kt"

NEW_TABLES = ("import_batches", "imported_rows")
NEW_INDICES = (
    "index_transactions_fingerprint",
    "index_transactions_importBatchId",
    "index_imported_rows_batchId",
    "index_imported_rows_txnId",
)
ALTERED = (
    ("accounts", ["aliases"]),
    ("categories", ["isIncome"]),
    ("transactions", ["importBatchId", "fingerprint", "timeWasInferred", "noSmsCounterpart"]),
)


def statements(src):
    """SQL passed to execSQL(), with Kotlin string concatenation folded back together.

    Parentheses are balanced rather than regex-matched: SQL contains its own parens,
    as in PRIMARY KEY(`id`), and a lazy match stops in the middle of the statement.
    """
    out = []
    for match in re.finditer(r"db\.execSQL\(", src):
        i, depth, in_string, buf = match.end(), 1, False, []
        while i < len(src) and depth > 0:
            c = src[i]
            if in_string:
                if c == "\\":
                    buf.append(src[i + 1])
                    i += 2
                    continue
                if c == '"':
                    in_string = False
                else:
                    buf.append(c)
            elif c == '"':
                in_string = True
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            i += 1
        out.append(normalise("".join(buf)))
    return out


def normalise(sql):
    return re.sub(r"\s+", " ", sql).strip().rstrip(";")


def main():
    database = json.load(open(SCHEMA))["database"]
    found = statements(open(SOURCE).read())
    failures = []

    def check(label, expected, predicate):
        mine = [s for s in found if predicate(s)]
        if not mine or mine[0] != expected:
            failures.append((label, expected, mine[0] if mine else "(missing)"))
            print("  MISMATCH %s" % label)
        else:
            print("  ok       %s" % label)

    print("tables")
    for entity in database["entities"]:
        table = entity["tableName"]
        if table not in NEW_TABLES:
            continue
        expected = normalise(entity["createSql"].replace("${TABLE_NAME}", table))
        check(table, expected,
              lambda s, t=table: s.upper().startswith("CREATE TABLE") and "`%s`" % t in s)

    print("indices")
    for entity in database["entities"]:
        for index in entity.get("indices", []):
            if index["name"] not in NEW_INDICES:
                continue
            expected = normalise(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            check(index["name"], expected, lambda s, n=index["name"]: n in s)

    print("added columns")
    for table, columns in ALTERED:
        entity = next(e for e in database["entities"] if e["tableName"] == table)
        for column in columns:
            field = next(f for f in entity["fields"] if f["columnName"] == column)
            alters = [s for s in found
                      if s.startswith("ALTER") and "`%s`" % column in s and "`%s`" % table in s]
            label = "%s.%s" % (table, column)
            if not alters:
                failures.append((label, "an ALTER TABLE statement", "(missing)"))
                print("  MISMATCH %s" % label)
                continue
            # Room compares defaults, so a DEFAULT clause here must have a matching
            # @ColumnInfo(defaultValue = ...) on the entity, and vice versa.
            if bool(field.get("defaultValue")) != ("DEFAULT" in alters[0]):
                failures.append((label, "defaultValue=%s" % field.get("defaultValue"), alters[0]))
                print("  MISMATCH %s  (default clause disagrees with the entity)" % label)
            else:
                print("  ok       %s" % label)

    if failures:
        print("\n%d mismatch(es):" % len(failures))
        for label, expected, mine in failures:
            print("\n  %s\n    room: %s\n    mine: %s" % (label, expected, mine))
        return 1
    print("\nmigration produces exactly the schema Room validates against")
    return 0


if __name__ == "__main__":
    sys.exit(main())
