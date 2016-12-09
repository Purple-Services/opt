(defproject opt "1.0.6-SNAPSHOT"
  :description "Purple optimization library."
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-http "2.1.0"]
                 ;; kludge to pull in json java classes
                 ;; can be removed after we port purpleOpt over to clojure
                 [com.twilio.sdk/twilio-java-sdk "4.2.0"]
                 [common "2.0.2-SNAPSHOT"]
                 [org.clojure/data.json "0.2.6"]]
  :java-source-paths ["src/java"]
  :profiles {:shared [{:dependencies
                       [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.0"]
                        [org.seleniumhq.selenium/selenium-java "2.47.1"]
                        [clj-webdriver "0.7.2"]
                        [ring "1.5.0"]
                        [pjstadig/humane-test-output "0.6.0"]]
                       :injections
                       [(require 'pjstadig.humane-test-output)
                        (pjstadig.humane-test-output/activate!)]}]
             :local [:shared :profiles/local]
             :dev [:shared :profiles/dev]
             :prod [:shared :profiles/prod]})
