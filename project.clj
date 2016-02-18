(defproject opt "0.1.0-SNAPSHOT"
  :description "Purple optimization library."
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.twilio.sdk/twilio-java-sdk "4.2.0"] ;; kludge to pull in json java classes, can be removed after we port purpleOpt over to clojure
                 ]
  :java-source-paths ["src/java"])
