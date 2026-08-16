# org-ietf-tcp

**RFC 9293 TCP — sequence arithmetic and the connection state machine, in
portable `.cljc`.**

No sockets, no buffers, no clock. `step` takes a connection and one event and
returns the next connection plus the segments to send; the caller passes
`:now-ms`, which is what makes TIME-WAIT testable without waiting out 2×MSL.

```clojure
(require '[tcp.state :as tcp])

(def c  (tcp/connection {:iss 1000}))
(def r1 (tcp/step c {:kind :connect}))              ; => :send [SYN]
(def r2 (tcp/step (:tcp/connection r1)
                  {:kind :segment
                   :segment {:tcp/seq 5000 :tcp/ack 1001 :tcp/flags #{:syn :ack}}
                   :now-ms 0}))
(tcp/established? (:tcp/connection r2))              ; => true
```

## Sequence arithmetic is its own namespace for a reason

Sequence numbers are 32 bits and **wrap**. `<` on the raw integers is not the
comparison TCP means: after a wrap, a small number can be newer than a large
one. A stack that compares raw integers works perfectly for the first four
gigabytes of a connection and then starts discarding valid segments.

`tcp.seq` implements RFC 9293 §3.4's modular comparison, and two numbers exactly
2^31 apart are reported as **unordered** rather than given an invented answer
(`ordered?` exists so a caller that must not act on an undefined ordering can
ask).

## What the state machine refuses

- **A RST outside the receive window** is dropped, not obeyed. Obeying it is how
  a blind off-path attacker kills connections.
- **A RST in SYN-SENT** is believed only when it acknowledges our SYN.
- **An unacceptable segment is answered with an ACK**, not with silence — the
  peer has to learn our window.
- **Data against a zero window** is never acceptable. That is one of the four
  cases in §3.10.7.4's table and the one hand-written stacks let through.
- **A RST to a closed connection is not answered.** Answering a RST with a RST
  is how two closed peers loop forever.

## Simultaneous open is in the table

Two peers can SYN each other at once, and SYN-SENT receiving a bare SYN goes to
SYN-RECEIVED. It is rare, it is exactly the arm that gets dropped when the table
is written from the common path, and it has a test whose name says so.

## What this is not

**Not a transmission stack.** No retransmission timers, no congestion control,
no Nagle, no window scaling, no options, no reassembly of out-of-order data.
Those are separable, and they are named here as absent rather than half-built.

`kotoba-lang/aiueos` carries a one-connection TCP *probe* whose own README says
"this is a probe, not a stack". This namespace is the connection half of what
would replace it; the transmission half is still unwritten.

## Retransmission

`tcp.rto` is RFC 6298's estimator and `tcp.retransmit` is the queue it drives.

RTO covers the round trip **and its variance** — an estimator tracking only the
mean retransmits on jitter, and every early retransmission is a segment the
receiver already has plus a congestion signal the sender invented. RTTVAR is
updated from the **old** SRTT, because updating SRTT first collapses the
deviation term exactly when a jump makes the headroom necessary.

**Karn's algorithm is enforced, not weighted.** An ACK for a retransmitted
segment is ambiguous — it may acknowledge the original or the copy — so the
sample is refused and reported as refused (`:karn`), which is not the same as no
measurement having been taken. The flag lives on the queue entry, so one
retransmitted segment and one clean segment in flight together still yield the
one sample that is entitled to be measured.

`due` returns only the **oldest** expired entry. Flushing the queue on one timer
turns a single loss into a burst the network already asked the sender not to
send.

## Congestion control

`tcp.congestion` is RFC 5681: slow start, congestion avoidance, fast retransmit
and fast recovery.

The window is a **guess about a path nobody can see**. A sender cannot observe
the bottleneck; it observes that segments arrived and that some did not, and
5681 turns those two observations into a number.

- **Three duplicate ACKs is a heuristic, not a proof.** A duplicate means the
  receiver got something out of order, which happens on loss *and* on
  reordering. Three is where 5681 bets that loss is likelier — a path that
  reorders heavily will make this sender halve for no reason. Said here so a
  reader does not assume the number was derived.
- **A timeout is harsher than duplicates because the evidence is.** Duplicates
  mean the receiver is still hearing something; a timeout means it is not. So a
  timeout drops cwnd to one segment while fast recovery only halves it.
- **Fast recovery inflates, and the inflation must be undone.** Leaving it
  deflates cwnd back to ssthresh — a sender that forgets keeps a window it never
  earned. That has its own test.
- The initial window follows 5681 (`min(4·MSS, max(2·MSS, 4380))`), not RFC
  6928's 10·MSS. The larger burst is available as `initial-window-10` because
  how much an unmeasured path can absorb belongs to whoever deploys this.

`may-send` knows only what the **network** will take. The receiver's advertised
window is the other limit and stays with the caller.

## Reassembly

`tcp.reassemble` holds in-window segments that arrived out of order and hands up
the prefix that has become contiguous. A receiver that discards everything after
a gap turns one lost segment into a whole window of retransmissions — and the
congestion controller reads those as a much worse path than the one it is on.

**Overlap has a policy and the policy is first-data-wins.** Segments can overlap
and the overlapping bytes can *disagree*. Two receivers resolving that
differently see two different streams from the same packets, which is the whole
family of overlap-based IDS evasions. This keeps the bytes it already has and
trims the arrival down to what is genuinely new; taking the newer bytes would
let anyone able to inject a later packet rewrite what the peer already sent.

**The queue is bounded and overrun is refused whole.** An unbounded out-of-order
queue is a memory-exhaustion primitive. The bound is charged against what stays
*held* — in-order data is delivered and never occupies the queue — and a
segment that would exceed it is refused entirely, because a trimmed fragment can
never be completed.

`gaps` reports the holes as `[start end)` pairs — what a SACK option would say
if this stack had one.

## Window scaling and timestamps

`tcp.options` is RFC 7323. The header's window field is 16 bits, so without
scaling a receiver cannot advertise more than 65535 bytes — which makes
`tcp.congestion` an exercise on any path with a large bandwidth-delay product,
because cwnd can grow all it likes and the advertised window is the binding
limit.

- **Both sides must offer it or neither uses it.** There is no partial adoption:
  a scaled window read as unscaled is 2^N too small and the other way round is
  2^N too large.
- **A SYN's window is never scaled.** The option agreeing the scale travels in
  that very segment. `apply-scale` takes `syn?` and refuses rather than trusting
  the caller to remember — this is the classic bug in the option.
- **The two shifts are not interchangeable.** We shift our own advertisements by
  what we offered and read theirs by what they offered. Swapping them only shows
  on asymmetric shifts, which is why it survives casual testing.
- **`advertise` returns nil for a window it cannot express** rather than
  truncating, because truncating advertises a smaller window and stalls
  throughput with no error anywhere.

Timestamps buy two things. **PAWS** rejects old duplicates that the sequence
space could have wrapped past, comparing modularly because the timestamp clock
wraps too. And **the echo lifts Karn's restriction**: `tcp.rto` refuses samples
from retransmitted segments because the ACK is ambiguous about which copy it
acknowledges, and the echo is not ambiguous. `rtt-sample` is a named function so
that lifting is deliberate rather than a caller forgetting a flag.

Only an in-sequence segment updates the recorded timestamp — recording from a
reordered arrival lets an old segment raise the bar against the ones behind it.

## What this is not

No SACK, no Nagle, no delayed-ACK logic, and no PMTU discovery. Named as absent
rather than half-built.

## Status

103 tests / 249 assertions. Shown to discriminate at every layer: replacing the
modular comparison with a raw `<` fails 8; removing the simultaneous-open arm
fails 2; dropping Karn's refusal and flushing the queue on one timer fails 5;
forgetting to deflate on leaving fast recovery and making a timeout as gentle as
a fast retransmit fails 5; taking the newer bytes on overlap plus unbounding
the out-of-order queue fails 8; and scaling the SYN, swapping the two shifts and
comparing timestamps raw fails 5.
