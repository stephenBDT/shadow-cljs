(ns shadow.remote.runtime.cljs.common
  (:require
    [cognitect.transit :as transit]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.env :as renv]
    [shadow.remote.runtime.cljs.js-builtins]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.tap-support :as tap-support]
    [shadow.remote.runtime.eval-support :as eval-support]))

(defprotocol IHostSpecific
  (do-repl-require [this require-msg done error])
  (do-repl-invoke [this invoke-msg]))

(defn transit-read [data]
  (let [t (transit/reader :json)]
    (transit/read t data)))

(defn transit-str [obj]
  (let [w (transit/writer :json)]
    (transit/write w obj)))

(declare interpret-actions)

(defn continue! [state]
  (interpret-actions state))

(defn abort! [{:keys [callback] :as state} action ex]
  (-> state
      (assoc :result :runtime-error
             :ex ex
             :ex-action action)
      (dissoc :runtime :callback)
      (callback)))

(defn interpret-action [{:keys [runtime] :as state} {:keys [type] :as action}]
  (case type
    :repl/set-ns
    (-> state
        (assoc :ns (:ns action))
        (continue!))

    :repl/require
    (let [{:keys [warnings internal]} action]
      (do-repl-require runtime action
        (fn [sources]
          (-> state
              (update :loaded-sources into sources)
              (update :warnings into warnings)
              (cond->
                (not internal)
                (update :results conj nil))
              (continue!)))
        (fn [ex]
          (abort! state action ex))))

    :repl/invoke
    (try
      (let [res (do-repl-invoke runtime action)]
        (-> state
            (update :results conj res)
            (continue!)))
      (catch :default ex
        (abort! state action ex)))

    ;; did I forget any?
    (throw (ex-info "unhandled repl action" {:state state :action action}))))

(defn interpret-actions [{:keys [queue] :as state}]
  (if (empty? queue)
    (let [{:keys [callback]} state]
      (-> state
          (dissoc :runtime :callback :queue)
          (callback)))

    (let [action (first queue)
          state (update state :queue rest)]
      (interpret-action state action))))

(defrecord Runtime [state-ref send-fn]
  api/IRuntime
  (relay-msg [this msg]
    (let [s (try
              (transit-str msg)
              (catch :default e
                (throw (ex-info "failed to encode relay msg" {:msg msg}))))]
      (send-fn s)))
  (add-extension [runtime key spec]
    (shared/add-extension runtime key spec))
  (del-extension [runtime key]
    (shared/del-extension runtime key))

  renv/IEvalCLJS
  (-eval-cljs [this input callback]
    ;; FIXME: define what input is supposed to look like
    ;; {:code "(some-cljs)" :ns foo.bar}
    (shared/call this
      {:op :cljs-compile
       :rid env/worker-rid
       :input input}

      {:cljs-actions
       (fn [{:keys [actions] :as msg}]
         (interpret-actions
           {:runtime this
            :callback callback
            :input input
            :actions actions
            :queue actions
            :ns (:ns input)
            :result :ok
            :results []
            :warnings []
            :loaded-sources []}))

       :cljs-compile-error
       (fn [{:keys [report]}]
         (callback
           {:result :compile-error
            :report report}))})))

(defn init-runtime! [{:keys [state-ref] :as runtime} socket-close]
  (shared/add-defaults runtime)

  (let [obj-support
        (obj-support/start runtime)

        tap-support
        (tap-support/start runtime obj-support)

        eval-support
        (eval-support/start runtime obj-support)

        interval
        (js/setInterval #(shared/run-on-idle state-ref) 1000)

        stop
        (fn []
          (js/clearTimeout interval)
          (eval-support/stop eval-support)
          (tap-support/stop tap-support)
          (obj-support/stop obj-support)
          (socket-close)
          (swap! renv/runtime-ref dissoc :obj-support :tap-support :eval-support :stop))]

    (reset! renv/runtime-ref
      {:runtime runtime
       :obj-support obj-support
       :tap-support tap-support
       :eval-support eval-support
       :stop stop})))

(defn stop-runtime! []
  (when-some [runtime @renv/runtime-ref]
    (let [{:keys [stop]} runtime]
      (stop))))