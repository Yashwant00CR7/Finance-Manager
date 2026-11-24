"""Try the trained model on a message.

    python -m smsml.predict "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to swiggy@ybl"
    echo "<message>" | python -m smsml.predict
"""

from __future__ import annotations

import pickle
import sys
from pathlib import Path

from smsml.crf import type_text

MODELS = Path(__file__).resolve().parent.parent / "models"


def main() -> None:
    with (MODELS / "span_crf.pkl").open("rb") as fh:
        tagger = pickle.load(fh)
    with (MODELS / "type_clf.pkl").open("rb") as fh:
        clf = pickle.load(fh)
    texts = sys.argv[1:] or [l.rstrip("\n") for l in sys.stdin if l.strip()]
    for text in texts:
        spans = tagger.predict([text])[0]
        probs = clf.predict_proba([type_text({"text": text})])[0]
        best = probs.argmax()
        print(text)
        print(f"  type: {clf.classes_[best]} ({probs[best]:.0%})")
        for s in spans:
            print(f"  {s['label']:<12} {text[s['start']:s['end']]}")
        print()


if __name__ == "__main__":
    main()
