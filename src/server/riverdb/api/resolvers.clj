(ns riverdb.api.resolvers
  (:require
    [riverdb.state :refer [db cx]]
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
    [com.wsscode.pathom.connect :as pc :refer [defresolver defmutation]]
    [com.wsscode.pathom.core :as p]
    [taoensso.timbre :as log :refer [debug]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.server.api-middleware :as fmw]
    [datomic.api :as d]
    [riverdb.api.tac-report :as tac]
    [riverdb.graphql.schema :refer [table-specs-ds specs-sorted specs-map]]
    [datascript.core :as ds]
    [thosmos.util :as tu :refer [walk-modify-k-vals limit-fn]]
    [clojure.walk :as walk]
    [clojure.string :as st]))

(defn add-conditions
  "add :where conditions like:
  [(> ?SiteVisitDate #inst \"2019-11-03T00:00:00.000-00:00\")]
  [(> ?SiteVisitDate #inst \"2020-11-03T00:00:00.000-00:00\")]
  from a map like:
  {:> #inst \"2019-11-03T00:00:00.000-00:00\"
   :< #inst \"2020-11-03T00:00:00.000-00:00\"}"
  [find arg conditions]
  (reduce-kv
    (fn [find k v]
      (cond
        (= k :>)
        (conj find [(list '> arg v)])
        (= k :<)
        (conj find [(list '< arg v)])))
    find conditions))

(defn add-filters [arg find-v filter-m]
  (try
    (reduce-kv
      (fn [find k v]
        (let [ent-k     (keyword "entity.ns" (namespace k))
              ent-nm    (name k)
              attr-spec (get-in specs-map [ent-k :entity/attrs k])
              ref?      (= (:attr/type attr-spec) :ref)
              inst?     (= (:attr/type attr-spec) :instant)
              string?   (string? v)
              map?      (map? v)
              arg?      (cond
                          (and ref? map?)
                          (symbol (str "?" (name (ffirst v))))
                          (and inst? map?)
                          (symbol (str "?" ent-nm)))
              find      (cond
                          (and ref? map?)
                          (add-filters arg? find v) ;; if it's a nested field, then recurse
                          (and inst? map?)
                          (add-conditions find arg? v) ;; if it's got where conditions, add the bindings
                          :else
                          find)
              v         (cond
                          ref?
                          (cond
                            string?
                            (Long/parseLong v)
                            map?
                            arg?)
                          inst?
                          (cond
                            map?
                            arg? ;; if it's a map of conditions, return the arg that they bind to
                            :else
                            v)
                          :else
                          v)]
          (conj find [arg k v])))
      find-v filter-m)
    (catch Exception ex (log/error "ADD-FILTERS ERROR" ex))))

(defn compare-fn [sortOrder]
  (cond
    (= sortOrder :desc)
    #(compare %2 %1)
    :else
    #(compare %1 %2)))

(defn lookup-resolve [env input]
  (try
    (let [params        (-> env :ast :params)
          query         (:query env)

          ids?          (:ids params)
          ids           (when ids?
                          (mapv #(Long/parseLong %) ids?))

          ent-key?      (-> env :ast :key)
          ent-name?     (name ent-key?)
          meta?         (st/ends-with? ent-name? "-meta")
          meta-key      (when meta?
                          ent-key?)

          ent-name      (if meta?
                          (subs ent-name? 0 (st/index-of ent-name? "-meta"))
                          ent-name?)
          ent-key       (if meta?
                          (keyword ent-name)
                          ent-key?)

          ent-type      (tu/ns-kw "entity.ns" (last (st/split ent-name #"\.")))
          spec          (get specs-map ent-type)
          ;; if we have a spec for a different lookup key, use it
          lookup?       (get spec :entity/lookup)
          lookup-key    (cond
                          lookup?
                          lookup?
                          ids?
                          ids
                          :else
                          ent-type)

          _             (log/debug "List Resolver -> params" params "ent-key" ent-key "ent-type" ent-type "lookup-key" lookup-key)
          ;meta?         (some #{:org.riverdb.meta/query-count} query)

          ;; if it's a meta query, we don't need any query fields
          query         (if meta?
                          [:db/id]
                          query)

          find          (if ids?
                          '[:find [(pull ?e qu) ...]
                            :in $ qu [?e ...]
                            :where]
                          '[:find [(pull ?e qu) ...]
                            :in $ qu ?typ
                            :where])


          ;;; If either from- or to- date were passed, join the `sitevisit` entity
          ;;; and bind its `SiteVisitDate` attribute to the `?date` variable.
          ;(or fromDate toDate)
          ;(update :where conj
          ;  '[?sv :sitevisit/SiteVisitDate ?date])
          ;
          ;;; If the `fromDate` filter was passed, do the following:
          ;;; 1. add a parameter placeholder into the query;
          ;;; 2. add an actual value to the arguments;
          ;;; 3. add a proper condition against `?date` variable
          ;;; (remember, it was bound above).
          ;fromDate
          ;(->
          ;  (update :in conj '?fromDate)
          ;  (update :args conj (jt/java-date fromDate))
          ;  (update :where conj
          ;    '[(> ?date ?fromDate)]))
          ;
          ;;; similar to ?fromDate
          ;toDate
          ;(->
          ;  (update :in conj '?toDate)
          ;  (update :args conj (jt/java-date toDate))
          ;  (update :where conj
          ;    '[(< ?date ?toDate)]))

          _ (debug "ADDING FILTERS ...")
          ;; add the filter conditions first.
          ;; TODO we can probably skip this if we have IDs, but leaving for now
          filter        (:filter params)
          find          (if filter
                          (add-filters '?e find filter)
                          find)

          _ (log/debug "POST FILTERS" find)

          ;; add the type condition because filters are *probably* more restrictive than the type?
          find          (cond
                          ids? ; if we have IDs, we don't need any more conditions
                          find

                          lookup?
                          (conj find `[~'?e ~lookup-key])

                          :else
                          (conj find '[?e :riverdb.entity/ns ?typ]))

          results       (d/q find
                          (db) query lookup-key)
          results-count (count results)
          _             (log/debug "\nLIST RESULTS for" lookup-key "\nCOUNT" results-count "\nFIND" find "\nQUERY" query "\nRESULTS" (first results))]

      ;; if it's a metadata query, branch before doing all the limits and sorts
      (if meta?
        ;; we return one record with the metadata fields
        (let [result {:org.riverdb.meta/query-count results-count}
              final  {meta-key result}]
          (debug "FINAL META RESULT" final)
          final)


        ;; if it's a regular query, then let's do the whole thing
        (let [limit          (get params :limit 25)
              offset         (get params :offset 0)
              sortField      (:sortField params)
              sortOrder      (:sortOrder params)

              nestedSort?    false
              childSortField nil

              ids            nil

              results        (if sortField
                               (let [sort-fn (if nestedSort?
                                               #(get-in % [sortField childSortField])
                                               sortField)]
                                 (sort-by sort-fn (compare-fn sortOrder) results))
                               results)

              results        (cond->> results

                               ; return exact list that was requested
                               (seq ids)
                               (fn [results]
                                 (log/debug "Returning Exact Results for IDS" ids)
                                 (let [id-map (into {} (for [res results]
                                                         [(:db/id res) res]))
                                       _      (log/debug "ID-MAP keys" (keys id-map))]
                                   (vec
                                     (for [id ids]
                                       (get id-map id)))))

                               (and limit (> limit 0))
                               (limit-fn limit offset))


              results        (walk-modify-k-vals results :db/id str)]
          (log/debug "FINAL RESULTS" (first results))
          {ent-key results})))

    (catch Exception ex (log/error "RESOLVER ERROR" ex))))



(def lookup-resolvers
  (vec
    (for [spec specs-sorted]
      (let [{:entity/keys [ns name pks attrs]} spec
            aks (mapv :attr/key attrs)]
        ;gid-key (keyword (str "riverdb.entity.ns." name) "gid")]
        {::pc/sym     (symbol name)
         ::pc/output  [{(keyword (str "org.riverdb.db." name))
                        (into [:db/id :riverdb.entity/ns :org.riverdb.meta/query-count] aks)}]
         ::pc/resolve lookup-resolve}))))

(def meta-resolvers
  (vec
    (for [spec specs-sorted]
      (let [{:entity/keys [ns name pks attrs]} spec
            aks (mapv :attr/key attrs)]
        {::pc/sym     (symbol (str name "-meta"))
         ::pc/output  [{(keyword (str "org.riverdb.db." name "-meta"))
                        (into [:db/id :riverdb.entity/ns :org.riverdb.meta/query-count] aks)}]
         ::pc/resolve lookup-resolve}))))



(defn id-resolve-factory [gid-key]
  (fn [env input]
    (log/debug "ID PULL RESOLVER" gid-key input)
    (try
      (let [query   (-> env ::p/parent-query)
            ;_       (log/debug "Lookup Resolver Key" id-key "Input" input "AST" ast "QUERY" query)

            id-val? (try
                      (Long/parseLong (get input gid-key))
                      (catch NumberFormatException _ nil))
            result  (when id-val?
                      (d/pull (db) query id-val?))
            result  (when result
                      (walk-modify-k-vals result :db/id str))]
        ;(log/debug "RESULT" result)
        result)
      (catch Exception ex (log/error ex)))))


(def id-resolvers
  (vec
    (for [spec specs-sorted]
      (let [{:entity/keys [ns name pks attrs]} spec
            aks   (mapv :attr/key attrs)
            gid-k (keyword (str "org.riverdb.db." name) "gid")]
        {::pc/sym       (symbol (str name "GID"))
         ::pc/input     #{gid-k}
         ::pc/output    (into [:db/id :riverdb.entity/ns] aks)
         ::pc/resolve   (id-resolve-factory gid-k)
         ::pc/transform pc/transform-batch-resolver}))))




(defresolver agency-project-years [env _]
  {;::pc/input     #{:agency}
   ::pc/output [:agency-project-years]}
  ;::pc/transform pc/transform-batch-resolver}
  (let [params (-> env :ast :params)]
    (log/info "QUERY :agency-project-years" params)
    {:agency-project-years (tac/get-agency-project-years (db) (:agencies params))}))





;(defresolver all-sitevisit-years [env _]
;  {::pc/output [:all-sitevisit-years]}
;  (let [params (-> env :ast :params)]
;    (do
;      (log/info "QUERY :all-sitevisit-years" params)
;      {:all-sitevisit-years (tac/get-sitevisit-years (db) (:project params))})))

(defresolver dataviz-data [env _]
  {::pc/output [:dataviz-data]}
  (let [params (-> env :ast :params)
        result (tac/get-dataviz-data (db) (:agency params) (:project params) (:year params))]
    (log/info "QUERY :dataviz-data" params (count result))
    {:dataviz-data result}))

(defresolver tac-report-data [env _]
  {::pc/output [:tac-report-data]}
  (let [params (-> env :ast :params)]
    (log/info "QUERY :tac-report-data" params)
    {:tac-report-data (if (:csv params)
                        (tac/get-annual-report-csv (:csv params))
                        (tac/get-annual-report (db) (:agency params) (:project params) (:year params)))}))

(def agency-query [:db/id :agencylookup/AgencyCode :agencylookup/AgencyDescr])

(>defn all-agencies
  "Returns a sequence of ..."
  [db]
  [any? => (s/coll-of map? :kind vector?)]
  (d/q '[:find [(pull ?e qu) ...]
         :in $ qu
         :where [?e :agencylookup/Active true]]
    db agency-query))

(defresolver agencylookup-resolver [{:keys [db] :as env} input]
  {;;GIVEN nothing key, gets all agency records
   ::pc/output [{:all-agencies agency-query}]}
  (let [params (-> env :ast :params)]
    (log/debug "Agency Lookup Input" input "Params?" params)
    {:all-agencies (all-agencies db)}))

(defresolver index-explorer [env _]
  {::pc/input  #{:com.wsscode.pathom.viz.index-explorer/id}
   ::pc/output [:com.wsscode.pathom.viz.index-explorer/index]}
  {:com.wsscode.pathom.viz.index-explorer/index
   (get env ::pc/indexes)})


(defresolver test-meta [_ _]
  {::pc/output [:test-meta]}
  {:test-meta (with-meta {:some :data} {:some :meta})})

(def resolvers [agencylookup-resolver agency-project-years tac-report-data dataviz-data meta-resolvers index-explorer test-meta])