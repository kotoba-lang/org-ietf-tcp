;; `kotoba/tcp/sack_core.kotoba` against `tcp.sack`.
;;
;; Two modules, so this drives `compile-project` rather than `compile-source`:
;; coverage is a sequence-space comparison and the object calls `tcp.seq-core`
;; instead of restating the wrap.
;;
;; What is checked is the per-entry arithmetic, which is where a SACK
;; implementation gets it wrong quietly. Both of the mistakes worth naming
;; cost throughput rather than correctness, so nothing reports them:
;;
;;   forgetting that SYN and FIN each consume a sequence number leaves the
;;   handshake byte outside every block, and the sender resends a segment the
;;   receiver already acknowledged, once per recovery, forever;
;;
;;   treating partial cover as cover marks an entry whose tail was never
;;   received, and those bytes are then never sent again.
;;
;; So the entries here straddle block edges by one byte in both directions.

(ns tcp.sack-kotoba-parity-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [tcp.sack :as sack]))

(def ^:private source-file
  (io/file (System/getProperty "user.dir") "kotoba" "tcp" "sack_core.kotoba"))

(def ^:private dep-file
  (io/file (System/getProperty "user.dir") "kotoba" "tcp" "seq_core.kotoba"))

(defn- source-available?
  "Fails rather than skips; a `when`-guard reports green on a checkout without
  the objects, which is a skip wearing a pass."
  []
  (let [present? (and (.exists source-file) (.exists dep-file))]
    (is present? (str "kotoba objects not found: " source-file " / " dep-file))
    present?))

(def ^:private kir
  (delay (:kir (compiler/compile-project
                {'tcp.seq-core (slurp dep-file)
                 'tcp.sack-core (slurp source-file)}
                'tcp.sack-core :wasm32-kotoba-v1 {}))))

(defn- call [f & args] (ir/execute @kir f (vec args)))

;; `covered?` and `entry-end` are private in tcp.sack -- they are the arithmetic
;; the public functions are built from, and the object exists to hold exactly
;; that arithmetic, so the test reaches them by var rather than restating them.
(def ^:private cljc-entry-end (var-get #'tcp.sack/entry-end))
(def ^:private cljc-covered? (var-get #'tcp.sack/covered?))

(def ^:private seqs
  [0 1 1000 4294967295 4294967294 2147483647 2147483648 2147483649])

(deftest kotoba-objects-are-present
  (source-available?))

(deftest max-blocks-agrees
  (when (source-available?)
    (doseq [ts? [false true]]
      (is (= (sack/max-blocks ts?) (call 'max-blocks ts?))
          (str "max-blocks timestamps " ts?
               " -- timestamps cost ten bytes, more than one block, so a "
               "connection using RFC 7323 reports one range fewer")))))

(deftest sack-permitted-agrees
  (when (source-available?)
    (doseq [ours [false true], theirs [false true]]
      (is (= (sack/permitted? {:sack-permitted ours} {:sack-permitted theirs})
             (call 'sack-permitted? ours theirs))
          (str "permitted? ours " ours " theirs " theirs)))))

(deftest entry-end-agrees
  (when (source-available?)
    (doseq [s seqs
            len [0 1 1460]
            syn? [false true]
            fin? [false true]]
      (is (= (cljc-entry-end {:seq s :len len :syn? syn? :fin? fin?})
             (call 'entry-end s len syn? fin?))
          (str "entry-end seq " s " len " len " syn " syn? " fin " fin?
               " -- SYN and FIN each consume one beyond the payload")))))

(deftest covered-agrees
  (when (source-available?)
    (doseq [s [1000 4294967290]
            len [0 1 100]
            syn? [false true]
            fin? [false true]
            :let [e {:seq s :len len :syn? syn? :fin? fin?}
                  end (cljc-entry-end e)]
            ;; block edges exactly at, one inside and one outside the entry in
            ;; both directions: partial cover must not count as cover.
            lo [(dec s) s (inc s)]
            hi [(dec end) end (inc end)]]
      (is (= (cljc-covered? e [lo hi]) (call 'covered? s end lo hi))
          (str "covered? entry [" s "," end ") block [" lo "," hi ")"
               " -- a block that contains all but one byte contains nothing, "
               "because the tail still has to be sent")))))
