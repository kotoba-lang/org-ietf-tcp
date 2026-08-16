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

## Status

26 tests / 69 assertions. Both halves shown to discriminate: replacing the
modular comparison with a raw `<` fails 8 assertions, and removing the
simultaneous-open arm fails 2.
