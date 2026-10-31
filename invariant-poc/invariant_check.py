"""Property primitives shared by the Actions runner and fast adapter tests."""

from dataclasses import asdict, dataclass
from typing import Protocol

from hypothesis import HealthCheck, find, settings, strategies as st
from hypothesis.errors import NoSuchExample


@dataclass(frozen=True)
class Update:
    title: str
    content: str


@dataclass(frozen=True)
class Observation:
    status: int
    author_before: int
    author_after: int


class Adapter(Protocol):
    def run(self, update: Update) -> Observation:
        """Reset to the same fixture, perform one update, and observe author IDs."""


def check_candidate(candidate: dict, base: Adapter, head: Adapter, *, max_examples: int = 100) -> dict:
    """Consume the structured API candidate using a trusted property implementation.

    The caller must confirm the candidate and supply the post-update-v1 adapters.
    No source code or expressions from the request are evaluated.
    """
    if (candidate.get("status") != "CANDIDATE"
            or candidate.get("ruleId") != "post-author-preserved-on-update"
            or candidate.get("version") != 1
            or candidate.get("adapter") != "post-update-v1"):
        raise ValueError("unsupported invariant candidate")
    result = check(base, head, max_examples=max_examples)
    return {**result, "ruleId": candidate["ruleId"], "version": 1}


def violates(obs: Observation) -> bool:
    if obs.status != 200:
        raise ValueError(f"fixture or request failed: HTTP {obs.status}")
    return obs.author_before != obs.author_after


def check(base: Adapter, head: Adapter, *, max_examples: int = 100) -> dict:
    """Search head then replay the exact counterexample twice on each side.

    Adapter errors propagate to the caller and must never be reported as a pass.
    """
    valid_text = st.text(alphabet="abcXYZ0123", min_size=1, max_size=20)
    updates = st.builds(Update, title=valid_text, content=valid_text)
    try:
        example = find(
            updates,
            lambda value: violates(head.run(value)),
            settings=settings(max_examples=max_examples, deadline=None, database=None,
                              suppress_health_check=[HealthCheck.too_slow]),
        )
    except NoSuchExample:
        return {"status": "no_counterexample", "max_examples": max_examples}

    base_runs = [base.run(example) for _ in range(2)]
    head_runs = [head.run(example) for _ in range(2)]
    base_outcomes = [violates(x) for x in base_runs]
    head_outcomes = [violates(x) for x in head_runs]
    if len(set(base_runs)) != 1 or len(set(head_runs)) != 1 or not all(head_outcomes):
        status = "flaky"
    elif all(base_outcomes):
        status = "existing_violation"
    else:
        status = "new_violation"
    return {"status": status, "input": asdict(example),
            "base": [asdict(x) for x in base_runs], "head": [asdict(x) for x in head_runs]}
