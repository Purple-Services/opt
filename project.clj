(defproject opt "0.1.0-SNAPSHOT"
  :description "Purple optimization library."
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-http "2.1.0"]
                 [com.twilio.sdk/twilio-java-sdk "4.2.0"] ;; kludge to pull in json java classes
                 [org.clojure/data.json "0.2.6"]
                 ]
  :java-source-paths ["src/java"])
