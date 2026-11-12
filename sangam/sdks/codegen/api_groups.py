# SPDX-License-Identifier: Apache-2.0
"""Partition of the operations into generated API classes (M-020; audit X2-04, owner decision 2026-11-12: no generated
class over the 800-line limit of the M-098 class-size gate, no exception for generated code).

Every contract tag starts as one class (Registry -> RegistryApi). When the class of a group would exceed the limit in
ANY of the five SDKs, the group is split by the next literal path segment below the segments it already shares
(/registry/parties/... -> RegistryPartiesApi, /registry/assets/... -> RegistryAssetsApi); operations that have no
further segment stay in the parent class. A group that cannot be split by path any further is cut into numbered parts
in contract order (Name2Api, Name3Api, ...). The same plan is used by every emitter, so the class and accessor names
are identical across languages, and the names only depend on the contract (stable across regenerations).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Callable

from contract_ir import Contract, Operation, camel, pascal

LIMIT = 800
MIN_OPS = 6


@dataclass
class Group:
    stem: str                    # e.g. "RegistryParties"; class = stem + "Api"
    tag: str
    prefix: tuple[str, ...]      # literal path segments every operation of the group shares (below the split depth)
    ops: list[Operation] = field(default_factory=list)

    @property
    def cls(self) -> str:
        return self.stem + "Api"

    @property
    def member(self) -> str:
        return camel(self.stem)

    @property
    def label(self) -> str:
        return self.tag + ("" if not self.prefix else " /" + "/".join(self.prefix))


def segments(path: str) -> list[str]:
    return [s for s in path.strip("/").split("/") if s and not s.startswith("{")]


def _split(g: Group, depth: int) -> list[Group] | None:
    """Splits g by the literal segment at index depth; None when that does not separate anything."""
    keyed: dict[str, list[Operation]] = {}
    rest: list[Operation] = []
    for o in g.ops:
        seg = segments(o.path)
        if len(seg) > depth:
            keyed.setdefault(seg[depth], []).append(o)
        else:
            rest.append(o)
    # cohesion: a sub-path becomes its own class only when it has a real resource behind it (MIN_OPS operations);
    # single action endpoints (/case-files/{id}/ack, .../close) stay with their parent instead of one class each
    for key in [k for k, ops in keyed.items() if len(ops) < MIN_OPS]:
        rest.extend(keyed.pop(key))
    if not keyed:
        return None
    order = {id(o): i for i, o in enumerate(g.ops)}
    rest.sort(key=lambda o: order[id(o)])
    out = []
    if rest:
        out.append(Group(g.stem, g.tag, g.prefix, rest))
    for key in sorted(keyed):
        out.append(Group(g.stem + pascal(key), g.tag, g.prefix + (key,), keyed[key]))
    return out


def plan(c: Contract, measure: Callable[[Contract, Group], int]) -> list[Group]:
    """measure returns the largest rendered line count of the group's class over all emitters."""
    todo = []
    for tag in c.tags:
        todo.append((Group(pascal(tag), tag, (), [o for o in c.operations if o.tag == tag]), 1))
    done: list[Group] = []
    while todo:
        g, depth = todo.pop(0)
        if measure(c, g) <= LIMIT:
            done.append(g)
            continue
        parts = None
        max_depth = max((len(segments(o.path)) for o in g.ops), default=0)
        d = depth
        while parts is None and d < max_depth:
            parts = _split(g, d)
            d += 1
        if parts is None:
            # no path segment separates the operations: numbered parts in contract order
            n = 2
            while True:
                size = max(1, len(g.ops) // n + (1 if len(g.ops) % n else 0))
                chunks = [g.ops[i:i + size] for i in range(0, len(g.ops), size)]
                cand = [Group(g.stem if i == 0 else f"{g.stem}{i + 1}", g.tag, g.prefix, ch)
                        for i, ch in enumerate(chunks)]
                if all(measure(c, x) <= LIMIT for x in cand) or size == 1:
                    done.extend(cand)
                    break
                n += 1
            continue
        for p in parts:
            # a residual (operations left in the parent) is only ever cut into numbered parts, never by a deeper
            # segment, so every class name is the parent name plus the one sub-path it stands for
            todo.append((p, d if p.prefix != g.prefix else 1 << 30))
    names = [g.cls for g in done]
    if len(names) != len(set(names)):
        raise ValueError(f"API class names collide: {sorted(n for n in names if names.count(n) > 1)}")
    order = {t: i for i, t in enumerate(c.tags)}
    done.sort(key=lambda g: (order[g.tag], len(g.prefix) > 0, g.stem))
    return done


def line_count(text: str) -> int:
    return len(re.findall(r"\n", text)) + (0 if text.endswith("\n") or not text else 1)
