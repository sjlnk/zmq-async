(ns com.keminglabs.zmq-async.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.core.async :refer
             [chan close! go <! >! <!! >!! alts!! timeout offer!]]
            [clojure.core.match :refer [match]]
            [clojure.set :refer [subset?]]
            [clojure.edn :refer [read-string]]
            [clojure.set :refer [map-invert]])
  (:import java.util.concurrent.LinkedBlockingQueue
           (org.zeromq ZMQ ZContext ZMQ$Socket ZMQ$Poller)))

;; Some terminology:
;;
;; sock:    ZeroMQ socket object
;; addr:    address of a sock (a string)
;; sock-id: randomly generated string ID created by the core.async thread
;;          when a new socket is requested
;; chan:    core.async channel
;; pairing: map entry of {sock-id {:out chan :in chan}}.
;;
;; All in/out labels are written relative to this namespace.

(def ^:dynamic *print-stderr* true)

(defn error [fmt & args]
  (binding [*out* *err*] (print (apply format (str fmt "\n") args))))

(defn send!
  [^ZMQ$Socket sock msg]
  (let [msg (if (coll? msg) msg [msg])]
    (loop [[head & tail] msg]
      ;;TODO: handle byte buffers.
      (let [res (.send sock head (if tail
                                   (bit-or ZMQ/NOBLOCK ZMQ/SNDMORE)
                                   ZMQ/NOBLOCK))]
        (cond
          (= false res) (error "*ERROR* message not sent on %s" sock)
          tail (recur tail))))))

(defn receive-all
  "Receive all data parts from the socket, returning a vector of byte arrays.
  If the socket does not contain a multipart message, returns a plain byte array."
  [^ZMQ$Socket sock]
  (loop [acc (transient [])]
    (let [new-acc (conj! acc (.recv sock))]
      (if (.hasReceiveMore sock)
        (recur new-acc)
        (let [res (persistent! new-acc)]
          (if (= 1 (count res))
            (first res) res))))))

(defn poll
  "Blocking poll that returns a [val, socket] tuple.
  If multiple sockets are ready, one is chosen to be read from nondeterministically."
  [socks]
  ;; TODO: what's the perf cost of creating a new poller all the time?
  (let [n      (count socks)
        poller (ZMQ$Poller. n)]
    (doseq [s socks]
      (.register poller s ZMQ$Poller/POLLIN))
    (.poll poller)

    ;; Randomly take the first ready socket and its message,
    ;; to match core.async's alts! behavior
    (->> (shuffle (range n))
         (filter #(.pollin poller %))
         first
         (.getSocket poller)
         ((juxt receive-all identity)))))


(defn zmq-looper
  "Runnable fn with blocking loop on zmq sockets.
  Opens/closes zmq sockets according to messages received on `zmq-control-sock`.
  Relays messages from zmq sockets to `async-control-chan`."
  [queue zmq-control-sock async-control-chan]
  (fn []
    ;; Socks is a map of string socket-ids to ZeroMQ socket objects
    ;; (plus a single :control keyword key associated with the
    ;; thread's control socket).
    (loop [socks {:control zmq-control-sock}]
      (let [[val sock] (poll (vals socks))
            id (get (map-invert socks) sock)
            ;; Hack coercion so we can have a pattern match against message
            ;; from control socket
            val (if (= :control id) (keyword (String. val)) val)]
        (assert (not (nil? id)))

        (match [id val]

          ;; A message indicating there's a message waiting for us to process
          ;; on the queue.
          [:control :sentinel]
          (let [msg (.take queue)]
            (match [msg]

              [[:register sock-id new-sock]]
              (recur (assoc socks sock-id new-sock))

              [[:close sock-id]]
              (do
                (.close (socks sock-id))
                (recur (dissoc socks sock-id)))

              [[:command sock-id cmd]]
              (do
                (try
                  ;; Execute cmd with socket corresponding to sock-id and
                  ;; when return value is non-nil return it to async-control-chan
                  ;; for further processing.
                  (when-let [res (cmd (socks sock-id))]
                    (>!! async-control-chan [:command sock-id res]))
                  (catch Exception e
                    (error "*ERROR* when executing a command: %s" e)))
                (recur socks))

              ;; Send a message out.
              [[sock-id outgoing-message]]
              (do
                (try
                  (send! (socks sock-id) outgoing-message)
                  (catch Exception e
                    (error "*ERROR* when sending a message: %s" e)))
                (recur socks))))

          [:control :shutdown]
          (doseq [[_ sock] socks]
            (.close sock))

          [:control msg]
          (throw (RuntimeException. (str "bad ZMQ control message: " msg)))

          ;; It's an incoming message, send it to the async thread to convey
          ;; to the application
          [incoming-sock-id msg]
          (do
            (>!! async-control-chan [incoming-sock-id msg])
            (recur socks)))))))

(defn sock-id-for-chan
  [c pairings]
  (first (for [[id chans] pairings :when ((set (vals chans)) c)] id)))

(defn command-zmq-thread!
  "Helper used by the core.async thread to relay a command to the ZeroMQ thread.
  Puts message of interest on queue and then sends a sentinel value over
  zmq-control-sock so that ZeroMQ thread unblocks."
  [zmq-control-sock queue msg]
  (.put queue msg)
  (send! zmq-control-sock "sentinel"))

(defn shutdown-pairing!
  "Close ZeroMQ socket with `id` and all associated channels."
  [[sock-id chanmap] zmq-control-sock queue]
  (command-zmq-thread! zmq-control-sock queue
                       [:close sock-id])
  (doseq [[_ c] chanmap]
    (when c (close! c))))

(defn async-looper
  "Runnable fn with blocking loop on channels.
  Controlled by messages sent over provided `async-control-chan`.
  Sends messages to complementary `zmq-looper` via provided
   `zmq-control-sock` (assumed to be connected)."
  [queue async-control-chan zmq-control-sock]
  (fn []
    ;; Pairings is a map of string id to {:out chan :in chan}
    ;; map, where existence of :out and :in depend on the type
    ;; of ZeroMQ socket.
    (loop [pairings {:control {:in async-control-chan}}]
      (let [in-chans (->> (vals pairings)
                          (map #(vals (select-keys % #{:in :ctl-in})))
                          flatten
                          (remove nil?))
            [val c]  (alts!! in-chans)
            id       (sock-id-for-chan c pairings)]
        (match [id val]
          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
          ;; Control messages

          ;; Register a new socket.
          [:control [:register sock chanmap]]
          (let [sock-id (str (gensym "zmq-"))]
            (command-zmq-thread! zmq-control-sock queue [:register sock-id sock])
            (recur (assoc pairings sock-id chanmap)))

          ;; Command socket
          [:control [:command sock-id result]]
          (do
            (when-let [ctl-out (get-in pairings [sock-id :ctl-out])]
              (when-not (offer! ctl-out result)
                (error "*WARNING* message dropped when passing to :ctl-out chan of %s"
                       sock-id))
              )
            (recur pairings))

          ;; Relay a message from ZeroMQ socket to core.async channel.
          [:control [sock-id msg]]
          (let [out (get-in pairings [sock-id :out])]
            (assert out)
            (when-not (offer! out msg)
              (error "*WARNING* message dropped when passing to :out chan of %s"
                     sock-id))
            (recur pairings))

          ;; The control channel has been closed, close all ZMQ
          ;; sockets and channels.
          [:control nil]
          (let [opened-pairings (dissoc pairings :control)]

            (doseq [p opened-pairings]
              (shutdown-pairing! p zmq-control-sock queue))

            (send! zmq-control-sock "shutdown")
            ;;Don't recur...
            nil)

          [:control msg] (throw (RuntimeException.
                                 (str "bad async control message: " msg)))

          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
          ;; Non-control messages

          ;; The channel was closed, close the corresponding socket.
          [id nil]
          (do
            (shutdown-pairing! [id (pairings id)] zmq-control-sock queue)
            (recur (dissoc pairings id)))

          ;; Convey the message to the ZeroMQ socket.
          [id msg]
          (do
            (cond

              ;; Normal message to deliver via socket.
              (= c (get-in pairings [id :in]))
              (command-zmq-thread! zmq-control-sock queue [id msg])

              ;; Command message to socket.
              (= c (get-in pairings [id :ctl-in]))
              (command-zmq-thread! zmq-control-sock queue [:command id msg])

              :else
              (throw (RuntimeException. "This should never happen."))
              )
            (recur pairings)))))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
(defn create-context
  "Creates a zmq-async context map containing the following keys:

  :zcontext           - jzmq ZContext object from which sockets are created
  :shutdown           - no-arg fn that shuts down this context, closing
                        allZeroMQ sockets
  :addr               - address of in-process ZeroMQ socket used to
                        control ZeroMQ thread
  :sock-server        - server end of zmq pair socket; must be bound via
                       (.bind addr) method before starting the zmq thread
  :sock-client        - client end of zmq pair socket; must be connected
                        via (.connect addr) method before starting the async
                        thread
  :async-control-chan - channel used to control async thread
  :zmq-thread
  :async-thread"
  ([] (create-context nil))
  ([name]
     (let [addr (str "inproc://" (gensym "zmq-async-"))
           zcontext (ZContext.)
           sock-server (.createSocket zcontext ZMQ/PAIR)
           sock-client (.createSocket zcontext ZMQ/PAIR)

           ;; Shouldn't have to have a large queue; it's okay to block core.async
           ;; thread puts since that'll give time for the ZeroMQ thread to catch up.
           queue (LinkedBlockingQueue. 8)

           async-control-chan (chan)

           zmq-thread (doto (Thread. (zmq-looper queue sock-server async-control-chan))
                        (.setName (format "zmq-looper-[%s]" (or name addr)))
                        (.setDaemon true))
           async-thread (doto (Thread. (async-looper queue async-control-chan
                                                     sock-client))
                          (.setName (format "core.async-looper-[%s]" (or name addr)))
                          (.setDaemon true))]

       {:zcontext zcontext
        :addr addr
        :sock-server sock-server
        :sock-client sock-client
        :queue queue
        :async-control-chan async-control-chan
        :zmq-thread zmq-thread
        :async-thread async-thread
        :shutdown #(close! async-control-chan)})))

(defn initialize!
  "Initializes a zmq-async context by binding/connecting both
   ends of the ZeroMQ control socket and starting both threads.
   Does nothing if zmq-thread is already started."
  [context]
  (let [{:keys [addr sock-server sock-client
                zmq-thread async-thread]} context]
    (when-not (.isAlive zmq-thread)
      (.bind sock-server addr)
      (.start zmq-thread)

      (.connect sock-client addr)
      (.start async-thread)))
  nil)

(def ^:private automagic-context
  "Default context used by any calls to `register-socket!`
  that don't specify an explicit context."
  (create-context "zmq-async-default-context"))

(defn register-socket!
  "Associate ZeroMQ `socket` with provided write-only `out`
  and read-only `in` ports.
  Accepts a map with the following keys:

  :context      - The zmq-async context under which the ZeroMQ socket
                  should be maintained; defaults to a global context if none
                  is provided
  :in           - Write-only core.async port on which you should place
                  outgoing messages
  :out          - Read-only core.async port on which zmq-async places
                  incoming messages; this port should never block
  :socket       - A ZeroMQ socket object that can be read from and/or written to
                  (i.e., already bound/connected to at least one address)
  :socket-type  - If a :socket is not provided, this socket-type will
                  be created for you; must be one of
                  :pair :dealer :router :pub :sub :req :rep :pull :push
                  :xreq :xrep :xpub :xsub
  :configurator - If a :socket is not provided, this function will be
                  used to configure a newly instantiated socket of
                  :socket-type; you should bind/connect to
                  at least one address within this function;
                  see http://zeromq.github.io/jzmq/javadocs/
                  for the ZeroMQ socket configuration options"
  [{:keys [context in out ctl-in ctl-out socket socket-type configurator]}]

  (when (and (nil? socket)
             (or (nil? socket-type) (nil? configurator)))
    (throw (IllegalArgumentException.
            (str "Must provide an instantiated and bound/connected ZeroMQ "
                 "socket or a socket-type and configurator fn."))))

  (when (and socket (or socket-type configurator))
    (throw (IllegalArgumentException.
            (str "You can provide a ZeroMQ socket OR a socket-type "
                 "and configurator, not both."))))
  (when (and (nil? out) (nil? in))
    (throw (IllegalArgumentException.
            "You must provide at least one of :out and :in channels.")))
  (let [context            (or context (doto automagic-context
                                         (initialize!)))
        ^ZMQ$Socket socket (or socket (doto (.createSocket (context :zcontext)
                                                           (case socket-type
                                                             :pair   ZMQ/PAIR
                                                             :pub    ZMQ/PUB
                                                             :sub    ZMQ/SUB
                                                             :req    ZMQ/REQ
                                                             :rep    ZMQ/REP
                                                             :xreq   ZMQ/XREQ
                                                             :xrep   ZMQ/XREP
                                                             :dealer ZMQ/DEALER
                                                             :router ZMQ/ROUTER
                                                             :xpub   ZMQ/XPUB
                                                             :xsub   ZMQ/XSUB
                                                             :pull   ZMQ/PULL
                                                             :push   ZMQ/PUSH))
                                        configurator))]
    (>!! (:async-control-chan context)
         [:register socket {:in in :out out :ctl-in ctl-in :ctl-out ctl-out}])))

(comment
  (initialize! automagic-context)

  )
