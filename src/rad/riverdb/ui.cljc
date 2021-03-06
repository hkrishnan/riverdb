(ns riverdb.ui
  (:require
    [clojure.string :as str]
    [edn-query-language.core :as eql]
    [riverdb.model :as model]
    [riverdb.model.person :as person]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]
       :cljs [com.fulcrologic.fulcro.dom :as dom :refer [div label input]])
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [com.fulcrologic.rad.type-support.date-time :as datetime]))

(form/defsc-form PersonForm [this props]
  {::form/id                person/uid
   ::form/attributes        [person/uid person/Name person/IsStaff person/Agency]
   ::form/default           {:person/IsStaff false}
   ::form/enumeration-order :person/Name
   ::form/cancel-route      ["people"]
   ::form/route-prefix      "person"
   ::form/title             "Edit Person"
   ::form/layout            [[:person/Name :person/Agency :person/IsStaff]]
   ::form/subforms          {:person/Agency {::form/ui            form/ToOneEntityPicker
                                             ::form/pick-one      {:options/query-key :org.riverdb.db.agencylookup
                                                                   :options/params    {:limit -1 :filter {:agencylookup/Active true}}
                                                                   :options/subquery  [:agencylookup/uuid :agencylookup/AgencyCode]
                                                                   :options/transform (fn [{:agencylookup/keys [uuid AgencyCode]}]
                                                                                        {:text AgencyCode :value [:agencylookup/uuid uuid]})}
                                             ::form/label         "Agency"
                                             ;; Use computed props to inform subform of its role.
                                             ::form/subform-style :inline}}})

;(def account-validator (fs/make-validator (fn [form field]
;                                            (case field
;                                              :account/email (let [prefix (or
;                                                                            (some-> form
;                                                                              (get :account/name)
;                                                                              (str/split #"\s")
;                                                                              (first)
;                                                                              (str/lower-case))
;                                                                            "")]
;                                                               (str/starts-with? (get form field) prefix))
;                                              (= :valid (model/all-attribute-validator form field))))))

(defsc PersonListItem [this {:person/keys [uuid Name Agency IsStaff] :as props}]
  {::report/columns         [:person/Name :person/Agency :person/IsStaff]
   ::report/column-headings ["Name" "Agency" "IsStaff"]
   ::report/row-actions     {:delete (fn [this id] (form/delete! this :person/uuid id))}
   ::report/edit-form       PersonForm
   :query                   [:person/uuid :person/Name :person/IsStaff :person/Agency]
   :ident                   :person/uuid}
  #_(dom/div :.item
      (dom/i :.large.github.middle.aligned.icon)
      (div :.content
        (dom/a :.header {:onClick (fn [] (form/edit! this AccountForm id))} name)
        (dom/div :.description
          (str (if active? "Active" "Inactive") ". Last logged in " last-login)))))

(def ui-person-list-item (comp/factory PersonListItem {:keyfn :person/uuid}))

(report/defsc-report PersonList [this props]
  {::report/BodyItem         PersonListItem
   ::report/source-attribute :org.riverdb.db.person
   ::report/parameters       {}
   ::report/route            "people"})