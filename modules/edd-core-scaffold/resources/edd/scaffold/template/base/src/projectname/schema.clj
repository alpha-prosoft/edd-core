(ns projectname.schema)

(def CreateItemCmd
  [:map
   [:cmd-id [:= :create-item]]
   [:id uuid?]
   [:attrs
    [:map
     [:name string?]]]])

(def GetItemQuery
  [:map
   [:query-id [:= :get-item]]
   [:id uuid?]])
