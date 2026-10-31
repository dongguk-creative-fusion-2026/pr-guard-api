import json
import os
import sys
from dataclasses import asdict
from urllib.request import Request, urlopen
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from hypothesis import find, settings, strategies as st
from hypothesis.errors import NoSuchExample
from invariant_check import Update, Observation, violates


def observe(endpoint, value):
    request = Request(endpoint, json.dumps(asdict(value)).encode(), {"Content-Type": "application/json"})
    with urlopen(request, timeout=10) as response:
        return Observation(**json.load(response))


def run(endpoint):
    if os.environ["SIDE"] == "HEAD":
        text = st.text(alphabet="abcXYZ0123", min_size=1, max_size=20)
        try:
            case = find(st.builds(Update, title=text, content=text),
                        lambda value: violates(observe(endpoint, value)),
                        settings=settings(max_examples=100, deadline=None, database=None))
        except NoSuchExample:
            return {"status": "no_counterexample", "max_examples": 100}
        with open(os.environ["CASE_PATH"], "w") as file:
            json.dump(asdict(case), file)
    else:
        with open(os.environ["CASE_PATH"]) as file:
            case = Update(**json.load(file))
    observations = [asdict(observe(endpoint, case)) for _ in range(2)]
    return {"status": "observed", "input": asdict(case), "observations": observations}


if __name__ == "__main__":
    result = run(sys.argv[1])
    with open(os.environ["RESULT_PATH"], "w") as file:
        json.dump(result, file)
