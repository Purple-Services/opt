(defproject opt "0.1.0-SNAPSHOT"
  :description "Purple optimization library."
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-http "2.1.0"]
                 ;; kludge to pull in json java classes
                 ;; can be removed after we port purpleOpt over to clojure
                 [com.twilio.sdk/twilio-java-sdk "4.2.0"]
                 [common "1.0.0-SNAPSHOT"]
                 [org.clojure/data.json "0.2.6"]]
  :java-source-paths ["src/java"]
  :jvm-opts ["-Dcom.sun.management.jmxremote"
           "-Dcom.sun.management.jmxremote.ssl=false"
           "-Dcom.sun.management.jmxremote.authenticate=false"
           "-Dcom.sun.management.jmxremote.port=43210"])
