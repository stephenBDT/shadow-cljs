(ns shadow.remote.runtime.cljs.websocket
  (:require
    ;; this will eventually replace shadow.cljs.devtools.client completely
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.browser :as browser]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.env :as renv]
    [shadow.remote.runtime.cljs.common :as common]
    [shadow.remote.runtime.cljs.js-builtins]))

(extend-type common/Runtime
  renv/IEvalJS
  (-eval-js [this code]
    (js* "(0,eval)(~{})" code))

  common/IHostSpecific
  (do-repl-invoke [this {:keys [js] :as msg}]
    (js* "(0,eval)(~{});" js))

  (do-repl-require [runtime {:keys [sources reload-namespaces js-requires] :as msg} done error]
    (let [sources-to-load
          (->> sources
               (remove (fn [{:keys [provides] :as src}]
                         (and (env/src-is-loaded? src)
                              (not (some reload-namespaces provides)))))
               (into []))]

      (if-not (seq sources-to-load)
        (done [])
        (shared/call runtime
          {:op :cljs-load-sources
           :rid env/worker-rid
           :sources (into [] (map :resource-id) sources-to-load)}

          {:cljs-sources
           (fn [{:keys [sources] :as msg}]
             (try
               (browser/do-js-load sources)
               (when (seq js-requires)
                 (browser/do-js-requires js-requires))
               (done sources-to-load)
               (catch :default ex
                 (error ex))))})))))

(defn start []
  (if-some [{:keys [stop]} @renv/runtime-ref]
    ;; if already connected. cleanup and call restart async
    ;; need to give the websocket a chance to close
    ;; only need this to support hot-reload this code
    ;; can't use :dev/before-load-async hooks since they always run
    (do (stop)
        (reset! renv/runtime-ref nil)
        (js/setTimeout start 10))

    (let [ws-url
          (str (env/get-ws-url-base) "/api/runtime"
               (if (exists? js/document)
                 "?type=browser"
                 "?type=browser-worker")
               "&build-id=" (js/encodeURIComponent env/build-id))

          socket
          (js/WebSocket. ws-url)

          send-fn
          (fn [msg]
            (.send socket msg))

          state-ref
          (atom (shared/init-state))

          runtime
          (common/Runtime. state-ref send-fn)]

      (common/init-runtime! runtime #(.close socket))

      (.addEventListener socket "message"
        (fn [e]
          (shared/process runtime (common/transit-read (.-data e)))
          ))

      (.addEventListener socket "open"
        (fn [e]
          ;; allow shared/process to send messages directly to relay
          ;; without being coupled to the implementation of exactly how
          ))

      (.addEventListener socket "close"
        (fn [e]
          (common/stop-runtime!)))

      (.addEventListener socket "error"
        (fn [e]
          (js/console.warn "tap-socket error" e)
          (common/stop-runtime!))))))

;; want things to start when this ns is in :preloads
(when (pos? env/worker-rid)
  (start))
