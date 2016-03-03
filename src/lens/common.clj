(ns lens.common
  (:require [schema.core :as s :refer [Keyword Any Uuid Int Str]]
            [lens.util :refer [NonBlankStr]]))

(def Params
  {Any Any})

(def Command
  "A command is something which a subject likes to do in a system.

  A command has an id which is an arbitrary UUID. The name of a command is a
  keyword like :create-subject in imperative form. The sub is the name of the
  subject which requested the command. The aid is the id of the aggregate on
  which the command should be performed. If no aggragate id is specified, the
  command will be performed against the whole database."
  {:id Uuid
   :name Keyword
   (s/optional-key :aid) Any
   :sub NonBlankStr
   (s/optional-key :params) Params
   Any Any})

(def Event
  "An event is something which happend in a system.

  An event has an id which is an arbitrary UUID. The name of an event is a
  possibly namespaced keyword, which is used as topic in the lens-sds.events
  topic exchange. The name should be a verb in past tense like :subject/created."
  {:id Uuid
   :cid Uuid
   :name Keyword
   :sub NonBlankStr
   (s/optional-key :t) Int
   (s/optional-key :data) {Any Any}
   Any Any})
