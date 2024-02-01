(ns sdk.aws.s3-it
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [sdk.aws.common :as common]
            [sdk.aws.s3 :as s3]
            [aws.ctx :as aws-ctx]
            [lambda.ctx :as lambda-ctx]
            [lambda.uuid :as uuid]
            [lambda.util :as util]))

(defn get-bucket
  [{:keys [aws] :as ctx}]
  (str
   (:account-id aws)
   "-"
   (:environment-name-lower ctx)
   "-it"))

(defn for-object
  [ctx key]
  (let [object {:s3 {:bucket {:name (get-bucket ctx)}
                     :object {:key key}}}]
    (log/info "Created object" object)
    object))

(defn for-object-with-content
  [ctx key content]
  (let [object {:s3 {:bucket {:name (get-bucket ctx)}
                     :object  {:key key
                               :content content}}}]
    (log/info "For object with content: " object)
    object))

(defn gen-key
  []
  (let [key (str (uuid/gen))]
    (log/info "Generation s3 key: " key)
    key))

(deftest test-s3-upload
  (let [ctx    (-> {}
                   lambda-ctx/init
                   aws-ctx/init)]
    (testing "Testing happy path of put and get object"
      (let [key (gen-key)
            data "sample-data"]
        (s3/put-object ctx (for-object-with-content ctx key data))
        (is (= data
               (slurp
                (s3/get-object ctx (for-object-with-content ctx key "sample-data")))))))

    (testing "Delete getting missing object"
      (let [key (gen-key)
            object (for-object ctx key)]
        (is (= nil
               (s3/get-object ctx object)))))

    (testing "Testing when there is error putting object to s3"
      (let [key (gen-key)
            data "sample-data"
            atempt (atom 0)
            original-function util/http-request]
        (with-redefs [util/http-request (fn [url request & {:keys [raw]}]
                                          (log/info "Attempt: " @atempt)
                                          (swap! atempt inc)
                                          (if (< @atempt 2)
                                            {:body (char-array data)
                                             :status 503}
                                            (original-function url request :raw raw)))]
          (s3/put-object ctx (for-object-with-content ctx key data)))
        (is (= 2
               @atempt))
        (is (= data
               (slurp
                (s3/get-object ctx (for-object-with-content ctx key "sample-data")))))))

    (testing "Testing when there is error getting-object-from s3"
      (let [key (gen-key)
            data "sample-data"
            atempt (atom 0)
            original-function util/http-request]
        (with-redefs [util/http-request (fn [url request & {:keys [raw]}]
                                          (log/info "Attempt: " @atempt)
                                          (when (= (:method request)
                                                   :put)
                                            (swap! atempt inc))
                                          (if (< @atempt 2)
                                            {:body (char-array data)
                                             :status 503}
                                            (original-function url request :raw raw)))]
          (s3/put-object ctx (for-object-with-content ctx key data))
          (let [resp (s3/get-object ctx (for-object-with-content ctx key "sample-data"))]
            (is (= 2
                   @atempt))
            (is (= data
                   (slurp resp)))))))))

(deftest test-s3-tagging
  (let [ctx    (-> {}
                   lambda-ctx/init
                   aws-ctx/init)]
    (testing "Testing happy path of put and get object tags"
      (let [key (gen-key)
            data "sample-data"
            object (for-object ctx key)
            tags [{:key "testkey"
                   :value "testvalue"}]]

        (s3/put-object ctx (assoc-in object [:s3 :object :content] data))
        (s3/put-object-tagging ctx {:object object
                                    :tags tags})
        (is (= tags
               (s3/get-object-tagging ctx object)))))

    (testing "Delete getting missing tagged"
      (let [key (gen-key)
            object (for-object ctx key)]
        (is (= nil
               (s3/get-object-tagging ctx object)))))

    (testing "Testing when there is error putting tags"
      (let [key (gen-key)
            data "sample-data"
            object (for-object ctx key)
            tags [{:key "testkey"
                   :value "testvalue"}]
            atempt (atom 0)
            original-function util/http-request]

        (s3/put-object ctx (assoc-in object [:s3 :object :content] data))
        (with-redefs [util/http-request (fn [url request & {:keys [raw]}]
                                          (log/info "Attempt: " @atempt)
                                          (swap! atempt inc)
                                          (if (< @atempt 2)
                                            {:body (char-array data)
                                             :status 503}
                                            (original-function url request :raw raw)))]
          (s3/put-object-tagging ctx {:object object
                                      :tags tags})
          (is (= 2
                 @atempt))
          (is (= tags
                 (s3/get-object-tagging ctx object))))))

    (testing "Testing when there is error getting tags"
      (let [key (gen-key)
            data "sample-data"
            object (for-object ctx key)
            tags [{:key "testkey"
                   :value "testvalue"}]
            atempt (atom 0)
            original-function util/http-request]

        (s3/put-object ctx (assoc-in object [:s3 :object :content] data))
        (s3/put-object-tagging ctx {:object object
                                    :tags tags})
        (with-redefs [util/http-request (fn [url request & {:keys [raw]}]
                                          (log/info "Attempt: " @atempt)
                                          (swap! atempt inc)
                                          (if (< @atempt 2)
                                            {:body (char-array data)
                                             :status 503}
                                            (original-function url request :raw raw)))]

          (let [response (s3/get-object-tagging ctx object)]
            (is (= 2
                   @atempt))
            (is (= tags
                   response))))))))

(deftest test-s3-delete
  (let [ctx    (-> {}
                   lambda-ctx/init
                   aws-ctx/init)]
    (testing "Testing happy path of put and delete"
      (let [key (gen-key)
            data "sample-data"
            object (for-object ctx key)]

        (s3/put-object ctx (assoc-in object [:s3 :object :content] data))
        (is (= data
               (slurp (s3/get-object ctx object))))
        (s3/delete-object ctx object)
        (is (= nil
               (s3/get-object ctx object)))))

    (testing "Delete missing object"
      (let [key (gen-key)
            object (for-object ctx key)]
        (is (= nil
               (s3/get-object ctx object)))))

    (testing "Testing when there is error deleteing"
      (let [key (gen-key)
            data "sample-data"
            object (for-object ctx key)
            atempt (atom 0)
            original-function util/http-request]

        (s3/put-object ctx (assoc-in object [:s3 :object :content] data))
        (is (= data
               (slurp (s3/get-object ctx object))))
        (with-redefs [util/http-request (fn [url request & {:keys [raw]}]
                                          (log/info "Attempt: " @atempt)
                                          (swap! atempt inc)
                                          (if (< @atempt 2)
                                            {:body (char-array data)
                                             :status 503}
                                            (original-function url request :raw raw)))]
          (s3/delete-object ctx object)
          (is (= 2
                 @atempt))
          (is (= nil
                 (s3/get-object ctx object))))))))

(defn get-aws-ctx
  []
  (-> {}
      lambda-ctx/init
      aws-ctx/init))

(deftest test-s3-upload-presigned-url
  (testing "Test s3 upload with pre-signed url"
    (let [ctx (get-aws-ctx)
          timeout 5000
          key (gen-key)
          data "sample-data"
          file (.getAbsolutePath
                (java.io.File/createTempFile "hello" ".txt"))
          _ (spit file data)
          md5 (util/path->md5base64 file)
          url (s3/presigned-url ctx {:method "PUT"
                                     :expires "360"
                                     :md5 md5
                                     :object (for-object ctx key)})

          wrong-file (.getAbsolutePath
                      (java.io.File/createTempFile "hello" ".txt"))
          _ (spit wrong-file "wrong data")

          resp (util/http-request url
                                  {:method :put
                                   :timeout timeout
                                   :headers {"Content-MD5" md5}
                                   :body (util/slurp-bytes file)}
                                  :raw true)
          fail-resp (util/http-request url
                                       {:method :put
                                        :timeout timeout
                                        :headers {"Content-MD5" md5}
                                        :body (util/slurp-bytes wrong-file)}
                                       :raw true)]

      (is (= 200
             (:status resp)))

      (is (= 400
             (:status fail-resp)))

      (is (string/includes?
           (:body fail-resp)
           "BadDigest"))

      (is (= data
             (slurp
              (s3/get-object ctx (for-object ctx key)))))

      (testing "With content-length"
        (let [long-key (gen-key)
              long-data "this is at least 10 characters long"
              long-file (.getAbsolutePath
                         (java.io.File/createTempFile "hello" ".txt"))
              _ (spit long-file long-data)
              long-md5 (util/path->md5base64 long-file)
              long-content-length 35    ;  (alength long-bytes)

                                        ;_ (log/infof "Content length: %s" long-content-length)
              _ (println long-md5)]
          (is (= 35
                 long-content-length))
          (testing "All good"
            (let [long-url (s3/presigned-url ctx {:method "PUT"
                                                  :expires "360"
                                                  :md5 long-md5
                                        ;:content-length long-content-length
                                                  :object (for-object ctx long-key)})
                  _ (println (str "curl -XPUT '"
                                  long-url
                                  "' -H \"Content-MD5: $(openssl dgst -md5 -binary "
                                  long-file
                                  " | openssl enc -base64)\" -d @" long-file))
                  resp (util/http-request long-url
                                          {:method :put
                                           :timeout timeout
                                           :headers {"Content-MD5" long-md5}
                                           :body (util/slurp-bytes long-file)}
                                          :raw true)]

              (println resp)
              (is (= 200
                     (:status resp)))))

          #_(testing "Change content length when signing"
              (let [long-url (s3/presigned-url ctx {:method "PUT"
                                                    :expires "360"
                                                    :md5 long-md5
                                                    :content-length 10
                                                    :object (for-object ctx long-key)})
                    _ (println (str "curl -XPUT '"
                                    long-url
                                    "' -H \"Content-MD5: $(openssl dgst -md5 -binary "
                                    long-file
                                    " | openssl enc -base64)\" -d @" long-file))
                    resp (util/http-request long-url
                                            {:method :put
                                             :timeout timeout
                                             :headers {"Content-MD5" long-md5
                                                       "Content-Length" 10}
                                             :body (util/slurp-bytes long-file)}
                                            :raw true)]
                (println resp)
                (is (= 403
                       (:status resp))))))))))

(deftest test-s3-with-content-length
  (testing "With content-length"
    (let [ctx (get-aws-ctx)
          timeout 5000
          long-key (gen-key)
          long-data "this is at least 10 characters long"
          long-file (.getAbsolutePath
                     (java.io.File/createTempFile "hello" ".txt"))
          _ (spit long-file long-data)
          long-md5 (util/path->md5base64 long-file)
          long-content-length 35    ;  (alength long-bytes)

                                        ;_ (log/infof "Content length: %s" long-content-length)
          _ (println long-md5)]
      (is (= 35
             long-content-length))
      (testing "All good"
        (let [long-url (s3/presigned-url ctx {:method "PUT"
                                              :expires "360"
                                              :md5 long-md5
                                              :content-length long-content-length
                                              :object (for-object ctx long-key)})
              _ (println (str "curl -XPUT '"
                              long-url
                              "' -H \"Content-MD5: $(openssl dgst -md5 -binary "
                              long-file
                              " | openssl enc -base64)\" -d @" long-file))
              resp (util/http-request long-url
                                      {:method :put
                                       :timeout timeout
                                       :headers {"Content-MD5" long-md5}
                                       :body (util/slurp-bytes long-file)}
                                      :raw true)]

          (is (= 200
                 (:status resp)))))

      (testing "Wrong length when signing"
        (let [long-url (s3/presigned-url ctx {:method "PUT"
                                              :expires "360"
                                              :md5 long-md5
                                              :content-length 10
                                              :object (for-object ctx long-key)})
              _ (println (str "curl -XPUT '"
                              long-url
                              "' -H \"Content-MD5: $(openssl dgst -md5 -binary "
                              long-file
                              " | openssl enc -base64)\" -d @" long-file))
              resp (util/http-request long-url
                                      {:method :put
                                       :timeout timeout
                                       :headers {"Content-MD5" long-md5
                                                 "Content-Length:" 10}
                                       :body (util/slurp-bytes long-file)}
                                      :raw true)]

          (is (= 400
                 (:status resp))))))))
