(ns projectname.main-test
  (:require [clojure.test :refer [deftest is]]
            [edd.test.fixture.dal :as mock]
            [edd.undertow.core :as undertow]
            [lambda.util :as util]
            [projectname.main :as sut]
            [projectname.service :as service])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers]))

(deftest health-check-test
  (let [server
        (sut/start (service/register mock/ctx)
                   :port 0
                   :host "127.0.0.1"
                   :consume [])]
    (try
      (let [request
            (.build (HttpRequest/newBuilder
                     (URI. (str "http://127.0.0.1:" (undertow/port server) "/health"))))

            ^HttpResponse response
            (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))]

        (is
         (= 200
            (.statusCode response)))

        (is
         (true?
          (:healthy (util/to-edn (.body response))))))
      (finally
        (undertow/stop server)))))
