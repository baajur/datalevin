(ns datalevin.lmdb
  "API for Key Value Store"
  (:refer-clojure :exclude [get iterate])
  (:require [datalevin.bits :as b]
            [datalevin.util :refer [raise]]
            [datalevin.constants :as c]
            [clojure.string :as s])
  (:import [org.lmdbjava Env EnvFlags Env$MapFullException Stat Dbi DbiFlags
            PutFlags Txn CursorIterable CursorIterable$KeyVal KeyRange]
           [clojure.lang IMapEntry]
           [java.util Iterator]
           [java.util.concurrent ConcurrentHashMap]
           [java.nio ByteBuffer]))

(def ^:no-doc default-env-flags [EnvFlags/MDB_NOTLS
                                 EnvFlags/MDB_NORDAHEAD])

(def ^:no-doc default-dbi-flags [DbiFlags/MDB_CREATE])

(defprotocol ^:no-doc IBuffer
  (put-key [this data k-type]
    "put data in key buffer, k-type can be :long, :byte, :bytes, :data")
  (put-val [this data v-type]
    "put data in val buffer, v-type can be :long, :byte, :bytes, :data"))

(defprotocol ^:no-doc IRange
  (put-start-key [this data k-type]
    "put data in start-key buffer, k-type can be :long, :byte, :bytes, :data
     or index type, :eav etc.")
  (put-stop-key [this data k-type]
    "put data in stop-key buffer, k-type can be :long, :byte, :bytes, :data
    or index type, :eav etc."))

(defprotocol ^:no-doc IRtx
  (close-rtx [this] "close the read-only transaction")
  (reset [this] "reset transaction so it can be reused upon renew")
  (renew [this] "renew and return previously reset transaction for reuse"))

(deftype ^:no-doc Rtx [^Txn txn
                       ^:volatile-mutable use
                       ^ByteBuffer kb
                       ^ByteBuffer start-kb
                       ^ByteBuffer stop-kb]
  IBuffer
  (put-key [_ x t]
    (b/put-buffer kb x t))
  (put-val [_ x t]
    (raise "put-val not allowed for read only txn buffer" {}))

  IRange
  (put-start-key [_ x t]
    (when x
      (.clear start-kb)
      (b/put-buffer start-kb x t)))
  (put-stop-key [_ x t]
    (when x
      (.clear stop-kb)
      (b/put-buffer stop-kb x t)))

  IRtx
  (close-rtx [_]
    (set! use false)
    (.close txn))
  (reset [this]
    (locking this
      (.reset txn)
      (set! use false)
      this))
  (renew [this]
    (locking this
      (when-not use
        (.renew txn)
        (set! use true)
        this))))

(defprotocol ^:no-doc IRtxPool
  (close-pool [this] "Close all transactions in the pool")
  (new-rtx [this] "Create a new read-only transaction")
  (get-rtx [this] "Obtain a ready-to-use read-only transaction"))

(deftype ^:no-doc RtxPool [^Env env
                           ^ConcurrentHashMap rtxs
                           ^:volatile-mutable ^long cnt]
  IRtxPool
  (close-pool [this]
    (locking this
      (doseq [^Rtx rtx (.values rtxs)] (close-rtx rtx))
      (.clear rtxs)
      (set! cnt 0)))
  (new-rtx [this]
    (locking this
      (when (< cnt c/+use-readers+)
        (let [rtx (->Rtx (.txnRead env)
                         false
                         (ByteBuffer/allocateDirect c/+max-key-size+)
                         (ByteBuffer/allocateDirect c/+max-key-size+)
                         (ByteBuffer/allocateDirect c/+max-key-size+))]
          (.put rtxs cnt rtx)
          (set! cnt (inc cnt))
          (reset rtx)
          (renew rtx)))))
  (get-rtx [this]
    (loop [i (.getId ^Thread (Thread/currentThread))]
      (let [^long i' (mod i cnt)
            ^Rtx rtx (.get rtxs i')]
        (or (renew rtx)
            (new-rtx this)
            (recur (long (inc i'))))))))

(defprotocol ^:no-doc IKV
  (put [this txn] [this txn put-flags]
    "Put kv pair given in `put-key` and `put-val` of dbi")
  (del [this txn] "Delete the key given in `put-key` of dbi")
  (get [this rtx] "Get value of the key given in `put-key` of rtx")
  (iterate [this rtx range-type] "Return a CursorIterable"))

(defn- key-range
  [range-type kb1 kb2]
  (case range-type
    :all               (KeyRange/all)
    :all-back          (KeyRange/allBackward)
    :at-least          (KeyRange/atLeast kb1)
    :at-least-back     (KeyRange/atLeastBackward kb1)
    :at-most           (KeyRange/atMost kb1)
    :at-most-back      (KeyRange/atMostBackward kb1)
    :closed            (KeyRange/closed kb1 kb2)
    :closed-back       (KeyRange/closedBackward kb1 kb2)
    :closed-open       (KeyRange/closedOpen kb1 kb2)
    :closed-open-back  (KeyRange/closedOpenBackward kb1 kb2)
    :greater-than      (KeyRange/greaterThan kb1)
    :greater-than-back (KeyRange/greaterThanBackward kb1)
    :less-than         (KeyRange/lessThan kb1)
    :less-than-back    (KeyRange/lessThanBackward kb1)
    :open              (KeyRange/open kb1 kb2)
    :open-back         (KeyRange/openBackward kb1 kb2)
    :open-closed       (KeyRange/openClosed kb1 kb2)
    :open-closed-back  (KeyRange/openClosedBackward kb1 kb2)))

(deftype ^:no-doc DBI [^Dbi db ^ByteBuffer kb ^:volatile-mutable ^ByteBuffer vb]
  IBuffer
  (put-key [_ x t]
    (b/put-buffer kb x t))
  (put-val [_ x t]
    (try
      (b/put-buffer vb x t)
      (catch Exception e
        (if (s/includes? (ex-message e) c/buffer-overflow)
          (let [size (b/measure-size x)]
            (set! vb (ByteBuffer/allocateDirect size))
            (b/put-buffer vb x t))
          (throw e)))))

  IKV
  (put [this txn]
    (put this txn nil))
  (put [_ txn flags]
    (.flip kb)
    (.flip vb)
    (if flags
      (.put db txn kb vb (into-array PutFlags flags))
      (.put db txn kb vb (make-array PutFlags 0)))
    (.clear kb)
    (.clear vb))
  (del [_ txn]
    (.flip kb)
    (.delete db txn kb)
    (.clear kb))
  (get [_ rtx]
    (let [^ByteBuffer kb (.-kb ^Rtx rtx)]
      (.flip kb)
      (let [res (.get db (.-txn ^Rtx rtx) kb)]
        (.clear kb)
        res)))
  (iterate [this rtx range-type]
    (let [^ByteBuffer start-kb (.-start-kb ^Rtx rtx)
          ^ByteBuffer stop-kb  (.-stop-kb ^Rtx rtx)]
      (.flip start-kb)
      (.flip stop-kb)
      (.iterate db (.-txn ^Rtx rtx) (key-range range-type start-kb stop-kb)))))

(defn- ^IMapEntry map-entry
  [^CursorIterable$KeyVal kv]
  (reify IMapEntry
    (key [_] (.key kv))
    (val [_] (.val kv))
    (equals [_ o] (and (= (.key kv) (key o)) (= (.val kv) (val o))))
    (getKey [_] (.key kv))
    (getValue [_] (.val kv))
    (setValue [_ _] (raise "IMapEntry is immutable"))
    (hashCode [_] (hash-combine (hash (.key kv)) (hash (.val kv))))))

(defprotocol ILMDB
  (close [this] "Close this LMDB env")
  (closed? [this] "Return true if this LMDB env is closed")
  (open-dbi
    [this dbi-name]
    [this dbi-name key-size]
    [this dbi-name key-size val-size]
    [this dbi-name key-size val-size flags]
    "Open a named DBI (i.e. sub-db) in the LMDB env")
  (clear-dbi [this dbi-name]
    "Clear data in the DBI (i.e sub-db), but leave it open")
  (drop-dbi [this dbi-name]
    "Clear data in the DBI (i.e. sub-db), then delete it")
  (get-dbi [this dbi-name]
    "Lookup open DBI (i.e. sub-db) by name, throw if it's not open")
  (entries [this dbi-name]
    "Get the number of data entries in a DBI (i.e. sub-db)")
  (transact [this txs]
    "Update DB, insert or delete key value pairs.

     `txs` is a seq of `[op dbi-name k v k-type v-type put-flags]`
     when `op` is `:put`, for insertion of a key value pair `k` and `v`;
     or `[op dbi-name k k-type]` when `op` is `:del`, for deletion of key `k`;

     `dbi-name` is the name of the DBI (i.e sub-db) to be transacted, a string.

     `k-type`, `v-type` and `put-flags` are optional.

    `k-type` indicates the type of `k`, and it can be one of `:data` (default),
    `:long`, `:byte`, `:bytes`, `:datom`, `:attr` or one of the index types
    `:eav`, `:aev`, `:ave`, or `:vae`

    `v-type` indicates the type of `v` can be one of `:data` (default),
    `:long`, `:byte`, `:bytes`, `:datom`, or `attr`

    `put-flags` is a vector of [LMDB put flags](https://www.javadoc.io/doc/org.lmdbjava/lmdbjava/latest/org/lmdbjava/PutFlags.html).

    Example:

            (transact lmdb
                      [ [:put \"a\" 1 2]
                        [:put \"a\" 'a 1]
                        [:put \"a\" 5 {}]
                        [:put \"a\" :annunaki/enki true :attr :data]
                        [:put \"a\" :datalevin [\"hello\" \"world\"]]
                        [:put \"a\" 42 (d/datom 1 :a/b {:id 4}) :long :datom]
                        [:put \"a\" (byte 0x01) #{1 2} :byte :data]
                        [:put \"a\" (byte-array [0x41 0x42]) :bk :bytes :data]
                        [:put \"a\" [-1 -235254457N] 5]
                        [:put \"a\" :a 4]
                        [:put \"a\" :bv (byte-array [0x41 0x42 0x43]) :data :bytes]
                        [:put \"a\" :long 1 :data :long]
                        [:put \"a\" 2 3 :long :long]
                        [:del \"a\" 1]
                        [:del \"a\" :non-exist] ])")
  (get-value
    [this dbi-name k]
    [this dbi-name k k-type]
    [this dbi-name k k-type v-type]
    [this dbi-name k k-type v-type ignore-key?]
    "Get kv pair of the specified key `k`, `k-type` and `v-type` can be
     `:data` (default), `:byte`, `:bytes`, `:attr`, `:datom` or `:long`;

     If `ignore-key?` is true (default `true`), only return the value,
     otherwise return `[k v]`, where `v` is the value

     Examples:

              (get-value lmdb \"a\" 1)
              ;;==> 2

              ;; specify data types
              (get-value lmdb \"a\" :annunaki/enki :attr :data)
              ;;==> true

              ;; return key value pair
              (get-value lmdb \"a\" 1 :data :data false)
              ;;==> [1 2]

              ;; key doesn't exist
              (get-value lmdb \"a\" 2)
              ;;==> nil ")
  (get-first
    [this dbi-name k-range]
    [this dbi-name k-range k-type]
    [this dbi-name k-range k-type v-type]
    [this dbi-name k-range k-type v-type ignore-key?]
    "Return the first kv pair in the specified key range;

     `k-range` is a vector `[range-type k1 k2]`, `range-type` can be one of
     `:all`, `:at-least`, `:at-most`, `:closed`, `:closed-open`, `:greater-than`,
     `:less-than`, `:open`, `:open-closed`, plus backward variants that put a
     `-back` suffix to each of the above, e.g. `:all-back`;

     `k-type` and `v-type` indicate the data type, and they can be `:data` (default),
     `:long`, `:byte`, `:bytes`, `:datom`, or `:attr`;

     Only the value will be returned if `ignore-key?` is `true`;
     If value is to be ignored, put `:ignore` as `v-type`

     If both key and value are ignored, return true if found an entry, otherwise
     return nil.

     Examples:


              (get-first lmdb \"c\" [:all] :long :long)
              ;;==> [0 1]

              ;; ignore value
              (get-first lmdb \"c\" [:all-back] :long :ignore)
              ;;==> [999 nil]

              ;; ignore key
              (get-first lmdb \"a\" [:greater-than 9] :long :data true)
              ;;==> {:some :data}

              ;; ignore both, this is like testing if the range is empty
              (get-first lmdb \"a\" [:greater-than 5] :long :ignore true)
              ;;==> true")
  (get-range
    [this dbi-name k-range]
    [this dbi-name k-range k-type]
    [this dbi-name k-range k-type v-type]
    [this dbi-name k-range k-type v-type ignore-key?]
    "Return a seq of kv pair in the specified key range;

     `k-range` is a vector `[range-type k1 k2]`, `range-type` can be one of
     `:all`, `:at-least`, `:at-most`, `:closed`, `:closed-open`, `:greater-than`,
     `:less-than`, `:open`, `:open-closed`, plus backward variants that put a
     `-back` suffix to each of the above, e.g. `:all-back`;

     `k-type` and `v-type` indicate the data type, and they can be `:data` (default),
     `:long`, `:byte`, `:bytes`, `:datom`, or `:attr`;

     Only the value will be returned if `ignore-key?` is `true`;
     If value is to be ignored, put `:ignore` as `v-type`

     Examples:


              (get-range lmdb \"c\" [:at-least 9] :long :long)
              ;;==> [[10 11] [11 15] [13 14]]

              ;; ignore value
              (get-range lmdb \"c\" [:all-back] :long :ignore)
              ;;==> [[999 nil] [998 nil]]

              ;; ignore keys, only return values
              (get-range lmdb \"a\" [:closed 9 11] :long :long true)
              ;;==> [10 11 12]

              ;; out of range
              (get-range lmdb \"c\" [:greater-than 1500] :long :ignore)
              ;;==> [] ")
  (get-some
    [this dbi-name pred k-range]
    [this dbi-name pred k-range k-type]
    [this dbi-name pred k-range k-type v-type]
    [this dbi-name pred k-range k-type v-type ignore-key?]
    "Return the first kv pair that has logical true value of `(pred x)`,
     where `pred` is a function, `x` is the `IMapEntry` fetched from the store,
     with both key and value fields being a `ByteBuffer`.

     `pred` can use [[datalevin.bits/read-buffer]] to read the content.

     `k-range` is a vector `[range-type k1 k2]`, `range-type` can be one of
     `:all`, `:at-least`, `:at-most`, `:closed`, `:closed-open`, `:greater-than`,
     `:less-than`, `:open`, `:open-closed`, plus backward variants that put a
     `-back` suffix to each of the above, e.g. `:all-back`;

     `k-type` and `v-type` indicate the data type, and they can be `:data` (default),
     `:long`, `:byte`, `:bytes`, `:datom`, or `:attr`;

     Only the value will be returned if `ignore-key?` is `true`;
     If value is to be ignored, put `:ignore` as `v-type`

     Examples:

              (require ' [datalevin.bits :as b])

              (def pred (fn [kv]
                         (let [^long k (b/read-buffer (key kv) :long)]
                          (> k 15)))

              (get-some lmdb \"c\" pred [:less-than 20] :long :long)
              ;;==> [16 2]

              ;; ignore key
              (get-some lmdb \"c\" pred [:greater-than 9] :long :data true)
              ;;==> 16 ")
  (range-filter
    [this dbi-name pred k-range]
    [this dbi-name pred k-range k-type]
    [this dbi-name pred k-range k-type v-type]
    [this dbi-name pred k-range k-type v-type ignore-key?]
    "Return a seq of kv pair in the specified key range, for only those
     return true value for `(pred x)`, where `pred` is a function, and `x`
     is an `IMapEntry`, with both key and value fields being a `ByteBuffer`.

     `pred` can use [[datalevin.bits/read-buffer]] to read the buffer content.

     `k-range` is a vector `[range-type k1 k2]`, `range-type` can be one of
     `:all`, `:at-least`, `:at-most`, `:closed`, `:closed-open`, `:greater-than`,
     `:less-than`, `:open`, `:open-closed`, plus backward variants that put a
     `-back` suffix to each of the above, e.g. `:all-back`;

     `k-type` and `v-type` indicate the data type, and they can be `:data` (default),
     `:long`, `:byte`, `:bytes`, `:datom`, or `:attr`;

     Only the value will be returned if `ignore-key?` is `true`;
     If value is to be ignored, put `:ignore` as `v-type`

     Examples:

              (require ' [datalevin.bits :as b])

              (def pred (fn [kv]
                         (let [^long k (b/read-buffer (key kv) :long)]
                          (> k 15)))

              (range-filter lmdb \"c\" pred [:less-than 20] :long :long)
              ;;==> [[16 2] [17 3]]

              ;; ignore key
              (range-filter lmdb \"c\" pred [:greater-than 9] :long :data true)
              ;;==> [16 17] "))

(defn- up-db-size [^Env env]
  (.setMapSize env (* 10 (-> env .info .mapSize))))

(defn- fetch-value
  [^DBI dbi ^Rtx rtx k k-type v-type ignore-key?]
  (put-key rtx k k-type)
  (when-let [^ByteBuffer bb (get dbi rtx)]
    (if ignore-key?
      (b/read-buffer bb v-type)
      [(b/expected-return k k-type) (b/read-buffer bb v-type)])))

(defn- fetch-first
  [^DBI dbi ^Rtx rtx [range-type k1 k2] k-type v-type ignore-key?]
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (let [^Iterator iter (.iterator iterable)]
      (when (.hasNext iter)
        (let [kv (map-entry (.next iter))
              v  (when (not= v-type :ignore) (b/read-buffer (val kv) v-type))]
          (if ignore-key?
            (if v v true)
            [(b/read-buffer (key kv) k-type) v]))))))

(defn- fetch-range
  [^DBI dbi ^Rtx rtx [range-type k1 k2] k-type v-type ignore-key?]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (loop [^Iterator iter (.iterator iterable)
           holder         (transient [])]
      (if (.hasNext iter)
        (let [kv      (map-entry (.next iter))
              v       (when (not= v-type :ignore)
                        (b/read-buffer (val kv) v-type))
              holder' (conj! holder
                             (if ignore-key?
                               v
                               [(b/read-buffer (key kv) k-type) v]))]
          (recur iter holder'))
        (persistent! holder)))))

(defn- fetch-some
  [^DBI dbi ^Rtx rtx pred [range-type k1 k2] k-type v-type ignore-key?]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (loop [^Iterator iter (.iterator iterable)]
      (when (.hasNext iter)
        (let [kv (map-entry (.next iter))]
          (if (pred kv)
            (let [v (when (not= v-type :ignore)
                      (b/read-buffer (.rewind ^ByteBuffer (val kv)) v-type))]
              (if ignore-key?
                v
                [(b/read-buffer (.rewind ^ByteBuffer (key kv)) k-type) v]))
            (recur iter)))))))

(defn- fetch-range-filtered
  [^DBI dbi ^Rtx rtx pred [range-type k1 k2] k-type v-type ignore-key?]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (loop [^Iterator iter (.iterator iterable)
           holder         (transient [])]
      (if (.hasNext iter)
        (let [kv (map-entry (.next iter))]
          (if (pred kv)
            (let [v       (when (not= v-type :ignore)
                            (b/read-buffer
                             (.rewind ^ByteBuffer (val kv)) v-type))
                  holder' (conj! holder
                                 (if ignore-key?
                                   v
                                   [(b/read-buffer
                                     (.rewind ^ByteBuffer (key kv)) k-type)
                                    v]))]
              (recur iter holder'))
            (recur iter holder)))
        (persistent! holder)))))

(deftype LMDB [^Env env ^String dir ^RtxPool pool ^ConcurrentHashMap dbis]
  ILMDB
  (close [this]
    (close-pool pool)
    (.close env))

  (closed? [this]
    (.isClosed env))

  (open-dbi [this dbi-name]
    (open-dbi this dbi-name c/+max-key-size+ c/+default-val-size+
              default-dbi-flags))
  (open-dbi [this dbi-name key-size]
    (open-dbi this dbi-name key-size c/+default-val-size+ default-dbi-flags))
  (open-dbi [this dbi-name key-size val-size]
    (open-dbi this dbi-name key-size val-size default-dbi-flags))
  (open-dbi [this dbi-name key-size val-size flags]
    (assert (not (closed? this)) "LMDB env is closed.")
    (let [kb  (ByteBuffer/allocateDirect key-size)
          vb  (ByteBuffer/allocateDirect val-size)
          db  (.openDbi env
                        ^String dbi-name
                        ^"[Lorg.lmdbjava.DbiFlags;" (into-array DbiFlags flags))
          dbi (->DBI db kb vb)]
      (.put dbis dbi-name dbi)
      dbi))

  (get-dbi [_ dbi-name]
    (or (.get dbis dbi-name)
        (raise "`open-dbi` was not called for " dbi-name {})))

  (clear-dbi [this dbi-name]
    (assert (not (closed? this)) "LMDB env is closed.")
    (try
      (with-open [txn (.txnWrite env)]
        (let [^DBI dbi (get-dbi this dbi-name)]
          (.drop ^Dbi (.-db dbi) txn))
        (.commit txn))
      (catch Exception e
        (raise "Fail to clear DBI: " dbi-name (ex-message e) {}))))

  (drop-dbi [this dbi-name]
    (assert (not (closed? this)) "LMDB env is closed.")
    (try
      (with-open [txn (.txnWrite env)]
        (let [^DBI dbi (get-dbi this dbi-name)]
          (.drop ^Dbi (.-db dbi) txn true)
          (.remove dbis dbi-name))
        (.commit txn))
      (catch Exception e
        (raise "Fail to drop DBI: " dbi-name (ex-message e) {}))))

  (entries [this dbi-name]
    (assert (not (closed? this)) "LMDB env is closed.")
    (let [^DBI dbi   (get-dbi this dbi-name)
          ^Rtx rtx   (get-rtx pool)
          ^Stat stat (.stat ^Dbi (.-db dbi) (.-txn rtx))]
      (.-entries stat)))

  (transact [this txs]
    (assert (not (closed? this)) "LMDB env is closed.")
    (try
      (with-open [txn (.txnWrite env)]
        (doseq [[op dbi-name k & r] txs
                :let                [dbi (get-dbi this dbi-name)]]
          (case op
            :put (let [[v kt vt flags] r]
                   (put-key dbi k kt)
                   (put-val dbi v vt)
                   (if flags
                     (put dbi txn flags)
                     (put dbi txn)))
            :del (let [[kt] r]
                   (put-key dbi k kt)
                   (del dbi txn))))
        (.commit txn))
      (catch Env$MapFullException e
        (up-db-size env)
        (transact this txs))
      (catch Exception e
          (raise "Fail to transact to LMDB: " (ex-message e) {:txs txs}))))

  (get-value [this dbi-name k]
    (get-value this dbi-name k :data :data true))
  (get-value [this dbi-name k k-type]
    (get-value this dbi-name k k-type :data true))
  (get-value [this dbi-name k k-type v-type]
    (get-value this dbi-name k k-type v-type true))
  (get-value [this dbi-name k k-type v-type ignore-key?]
    (assert (not (closed? this)) "LMDB env is closed.")
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-value dbi rtx k k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-value: " (ex-message e)
                 {:dbi dbi-name :k k :k-type k-type :v-type v-type}))
        (finally (reset rtx)))))

  (get-first [this dbi-name k-range]
    (get-first this dbi-name k-range :data :data false))
  (get-first [this dbi-name k-range k-type]
    (get-first this dbi-name k-range k-type :data false))
  (get-first [this dbi-name k-range k-type v-type]
    (get-first this dbi-name k-range k-type v-type false))
  (get-first [this dbi-name k-range k-type v-type ignore-key?]
    (assert (not (closed? this)) "LMDB env is closed.")
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-first dbi rtx k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-first: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx)))))

  (get-range [this dbi-name k-range]
    (get-range this dbi-name k-range :data :data false))
  (get-range [this dbi-name k-range k-type]
    (get-range this dbi-name k-range k-type :data false))
  (get-range [this dbi-name k-range k-type v-type]
    (get-range this dbi-name k-range k-type v-type false))
  (get-range [this dbi-name k-range k-type v-type ignore-key?]
    (assert (not (closed? this)) "LMDB env is closed.")
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-range dbi rtx k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-range: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx)))))

  (get-some [this dbi-name pred k-range]
    (get-some this dbi-name pred k-range :data :data false))
  (get-some [this dbi-name pred k-range k-type]
    (get-some this dbi-name pred k-range k-type :data false))
  (get-some [this dbi-name pred k-range k-type v-type]
    (get-some this dbi-name pred k-range k-type v-type false))
  (get-some [this dbi-name pred k-range k-type v-type ignore-key?]
    (assert (not (closed? this)) "LMDB env is closed.")
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-some dbi rtx pred k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-some: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx)))))

  (range-filter [this dbi-name pred k-range]
    (range-filter this dbi-name pred k-range :data :data false))
  (range-filter [this dbi-name pred k-range k-type]
    (range-filter this dbi-name pred k-range k-type :data false))
  (range-filter [this dbi-name pred k-range k-type v-type]
    (range-filter this dbi-name pred k-range k-type v-type false))
  (range-filter [this dbi-name pred k-range k-type v-type ignore-key?]
    (assert (not (closed? this)) "LMDB env is closed.")
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-range-filtered dbi rtx pred k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to range-filter: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx))))))

(defn open-lmdb
  "Open an LMDB database. `dir` is a string path where the data are to be stored.
  `size` is the initial DB size in MB. `flags` are [LMDB EnvFlags](https://www.javadoc.io/doc/org.lmdbjava/lmdbjava/latest/index.html)."
  ([dir]
   (open-lmdb dir c/+init-db-size+ default-env-flags))
  ([dir size flags]
   (let [file          (b/file dir)
         builder       (doto (Env/create)
                         (.setMapSize (* ^long size 1024 1024))
                         (.setMaxReaders c/+max-readers+)
                         (.setMaxDbs c/+max-dbs+))
         ^Env env      (.open builder file (into-array EnvFlags flags))
         ^RtxPool pool (->RtxPool env (ConcurrentHashMap.) 0)]
     (new-rtx pool)
     (->LMDB env dir pool (ConcurrentHashMap.)))))
