# Native AIUEOS consumer verification

This private repository owns the TCP source used by AIUEOS. The consumer
workflow pins exact AIUEOS/Amu revisions and the original TCP source commit.
It uses the repository's existing read-only Actions token. Private sources,
logs and artifacts stay in this private CI context; do not copy source into
public AIUEOS/Amu trees or change repository visibility.

CI/CD uses native Amu only. Do not add Java, Clojure, nbb, babashka or GraalVM
fallbacks. Python/Git stage/hash inputs and observe processes; QEMU observes
the native-produced guest. This is a full source-graph artifact reproduction
and DMA component execution gate, not full kernel/live/media/desktop proof.
The compiler/controller fixed point is a separate exact-head Amu gate.

Track this foundation integration in Kotoba Labs Linear JUN-31. Preserve
unrelated source changes and the immutable dependency source pins.
