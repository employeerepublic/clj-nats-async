(ns clj-nats-async.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [manifold.stream :as s])
  (:import [nats.client NatsConnector MessageHandler Message]))

(defn create-nats
  "creates a Nats connection, returning a Nats object
   - urls : nats server urls, either a seq or comma separated"
  [& urls]
  (let [nc (NatsConnector.)]
    (doseq [url (flatten (map #(str/split % #",") urls))]
      (.addHost nc url))
    (.connect nc)))

(defprotocol INatsMessage
  (msg-body [self]))

(defrecord NatsMessage [nats-message]
  INatsMessage
  (msg-body [self] (edn/read-string (.getBody nats-message))))

(defn ^:private create-nats-subscription
  [nats subject {:keys [queue-group max-messages] :as opts} stream]
  (.subscribe
   nats
   subject
   queue-group
   max-messages
   (into-array
    MessageHandler
    [(reify
       MessageHandler
       (onMessage [self m]
         (s/put! stream (NatsMessage. m))))])))

(defn subscribe
  "returns a a Manifold source-only stream of INatsMessages from a NATS subject.
   close the stream to dispose of the subscription"
  ([nats subject] (subscribe nats subject {}))
  ([nats subject opts]
   (let [stream (s/stream)
         source (s/source-only stream)
         nats-subscription (create-nats-subscription nats subject opts stream)]

     (s/on-closed stream (fn []
                           (.close nats-subscription)))

     source)))

(defn publish
  "publish a message
  - subject-or-fn : either a string specifying a fixed subject or a
                     (fn [item] ...) which extracts a subject from an item"
  ([nats subject-or-fn] (publish nats subject-or-fn "" {}))
  ([nats subject-or-fn body] (publish nats subject-or-fn body {}))
  ([nats subject-or-fn body {:keys [reply-to] :as opts}]
   (let [is-subject-fn? (or (var? subject-or-fn) (fn? subject-or-fn))
         subject (if is-subject-fn? (subject-or-fn body) subject-or-fn)]
     (if subject
       (.publish nats subject (pr-str body) reply-to)
       (log/warn (ex-info (str "no subject " (if is-subject-fn? "extracted" "given")) {:body body}))))))

(defn publisher
  "returns a Manifold sink-only stream which publishes items put on the stream
   to NATS"
  ([nats subject-or-fn]
   (let [stream (s/stream)]
     (s/consume (fn [body]
                  (publish nats subject-or-fn body))
                stream)
     (s/sink-only stream))))

(defn pubsub
  "returns a Manifold source+sink stream for a single NATS subject.
   the source returns INatsMessages, while the sink accepts
   strings"
  ([nats subject] (pubsub nats subject {}))
  ([nats subject opts]
   (let [pub-stream (s/stream)
         sub-stream (s/stream)

         nats-subscription (create-nats-subscription nats subject opts sub-stream)]

     (s/consume (fn [body] (publish nats subject body)) pub-stream)

     (s/on-closed sub-stream (fn [] (.close nats-subscription)))

     (s/splice pub-stream sub-stream))))
